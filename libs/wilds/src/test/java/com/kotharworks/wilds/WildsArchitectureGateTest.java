/*
 * Copyright 2026 Samuel "Lord-Windy" Brown
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.kotharworks.wilds;

import com.kotharworks.wilds.seed.SeededRng;
import com.kotharworks.wilds.sim.EntityTable;
import com.kotharworks.wilds.state.WorldState;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.BootstrapMethodEntry;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.TypeKind;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.ConstantDynamicEntry;
import java.lang.classfile.constantpool.LoadableConstantEntry;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.lang.classfile.constantpool.MethodHandleEntry;
import java.lang.classfile.constantpool.MethodTypeEntry;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.ConvertInstruction;
import java.lang.classfile.instruction.ExceptionCatch;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.NewMultiArrayInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.classfile.instruction.NewPrimitiveArrayInstruction;
import java.lang.classfile.instruction.NewReferenceArrayInstruction;
import java.lang.classfile.instruction.OperatorInstruction;
import java.lang.classfile.instruction.TypeCheckInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.math.BigInteger;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.DoubleSummaryStatistics;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Random;
import java.util.SequencedMap;
import java.util.SequencedSet;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

import javax.crypto.Cipher;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JDK 25 bytecode &amp; reflection gate keeping {@code libs/wilds} pure and deterministic.
 */
class WildsArchitectureGateTest {

    private static final Set<String> BANNED_MEMBERS = Set.of(
            "java/lang/System.currentTimeMillis",
            "java/lang/System.nanoTime",
            "java/lang/System.identityHashCode",
            "java/lang/System.in",
            "java/lang/System.out",
            "java/lang/System.err",
            "java/lang/Math.random",
            "java/lang/StrictMath.random",
            "java/lang/Thread.sleep",
            "java/util/UUID.randomUUID",
            "java/util/Set.of",
            "java/util/Set.copyOf",
            "java/util/Map.of",
            "java/util/Map.ofEntries",
            "java/util/Map.copyOf");

    private static final Set<String> BANNED_TYPES = Set.of(
            "java/util/Date", "java/util/Calendar", "java/util/GregorianCalendar",
            "java/util/Timer", "java/util/TimerTask",
            "java/util/Random", "java/util/concurrent/ThreadLocalRandom",
            "java/security/SecureRandom", "java/util/SplittableRandom",
            "java/util/HashMap", "java/util/LinkedHashMap",
            "java/util/HashSet", "java/util/LinkedHashSet",
            "java/util/IdentityHashMap", "java/util/WeakHashMap",
            "java/util/concurrent/ConcurrentHashMap",
            "java/lang/Float", "java/lang/Double",
            "java/util/OptionalDouble", "java/util/DoubleSummaryStatistics",
            "java/io/File", "java/io/FileInputStream", "java/io/FileOutputStream",
            "java/io/FileReader", "java/io/FileWriter", "java/io/RandomAccessFile",
            "java/lang/ProcessBuilder", "java/lang/Runtime");

    private static final List<String> BANNED_PREFIXES = List.of(
            "java/time/",
            "java/util/random/",
            "java/nio/file/",
            "java/net/",
            "java/sql/",
            "javax/sql/",
            "com/kotharworks/auth/");

    private static final List<Class<?>> BANNED_STATE_TYPES = List.of(
            float.class, double.class, Float.class, Double.class,
            OptionalDouble.class, DoubleSummaryStatistics.class,
            HashMap.class, HashSet.class, IdentityHashMap.class, ConcurrentHashMap.class,
            SeededRng.class);

    /**
     * Explicit allowlist of {@code java.*} types permitted inside simulation state.
     * Unsorted {@link Map}/{@link Set}/{@link SequencedMap}/{@link SequencedSet}/{@link Collection},
     * {@link Object}, and {@link OptionalDouble} are intentionally excluded so randomized
     * {@code ImmutableCollections} ({@code Set.of}/{@code Map.of}) and hash maps can never hide
     * behind an interface field.
     */
    private static final Set<Class<?>> ALLOWED_JDK_STATE_TYPES = Set.of(
            String.class, Boolean.class, Byte.class, Short.class, Character.class,
            Integer.class, Long.class, BigInteger.class,
            Optional.class, OptionalInt.class, OptionalLong.class,
            List.class, ArrayList.class,
            SortedMap.class, NavigableMap.class, TreeMap.class,
            SortedSet.class, NavigableSet.class, TreeSet.class);

    private record VisitedStateType(Type type, Map<TypeVariable<?>, Type> bindings) {}

    @Test
    void allProductionClassesAreFreeOfForbiddenReferences() throws IOException {
        Path classesDir = productionClassesDir();
        assertTrue(Files.isDirectory(classesDir), "missing compiled classes: " + classesDir.toAbsolutePath());
        List<Path> classFiles;
        try (var stream = Files.walk(classesDir)) {
            classFiles = stream.filter(p -> p.getFileName().toString().endsWith(".class")).sorted().toList();
        }
        assertTrue(!classFiles.isEmpty(), "no production classes under " + classesDir);
        List<String> violations = new ArrayList<>();
        for (Path classFile : classFiles) {
            violations.addAll(forbiddenReferences(ClassFile.of().parse(Files.readAllBytes(classFile))));
        }
        assertTrue(violations.isEmpty(), () -> "Forbidden API references:\n" + String.join("\n", violations));
    }

    @Test
    void removedProcgenModuleDoesNotExistDuringBaseStructurePhase() {
        // Phase 0 invariant (Ticket 01): update to scan libs/procgen when introduced in M1.
        Path procgenDir = productionClassesDir().resolve("../../../procgen").normalize();
        assertFalse(Files.exists(procgenDir),
                "stale libs/procgen directory must not remain on disk: " + procgenDir);
    }

    @Test
    void scannerCatchesWallClockNanoIdentityHashAndRandomUuid() {
        assertTrue(forbiddenReferences(model(WallClockUser.class)).stream()
                .anyMatch(v -> v.endsWith("-> java/lang/System.currentTimeMillis")));
        assertTrue(forbiddenReferences(model(NanoClockUser.class)).stream()
                .anyMatch(v -> v.endsWith("-> java/lang/System.nanoTime")));
        assertTrue(forbiddenReferences(model(IdentityHashUser.class)).stream()
                .anyMatch(v -> v.endsWith("-> java/lang/System.identityHashCode")));
        assertTrue(forbiddenReferences(model(RandomUuidUser.class)).stream()
                .anyMatch(v -> v.endsWith("-> java/util/UUID.randomUUID")));
    }

    @Test
    void scannerCatchesMethodReferencesStaticInitAndInterfaceMethods() {
        assertTrue(forbiddenReferences(model(ClockRefUser.class)).stream()
                .anyMatch(v -> v.endsWith("-> java/lang/System.currentTimeMillis")));
        assertTrue(forbiddenReferences(model(RandomRefUser.class)).stream()
                .anyMatch(v -> v.endsWith("-> java/lang/Math.random")));
        assertTrue(forbiddenReferences(model(StaticInitClockUser.class)).stream()
                .anyMatch(v -> v.contains("<clinit> -> java/lang/System.currentTimeMillis")));
        assertTrue(forbiddenReferences(model(DefaultMethodClockInterface.class)).stream()
                .anyMatch(v -> v.contains("now -> java/lang/System.nanoTime")));
        assertTrue(forbiddenReferences(model(AbstractForbiddenSignatureInterface.class)).stream()
                .anyMatch(v -> v.contains("instant -> java/time/Instant")));
    }

    @Test
    void scannerCatchesUnseededRandomIoPrefixesDescriptorsAndArrays() {
        assertTrue(forbiddenReferences(model(UnseededRandomUser.class)).stream()
                .anyMatch(v -> v.contains("java/util/Random")));
        assertTrue(forbiddenReferences(model(RandomGeneratorUser.class)).stream()
                .anyMatch(v -> v.contains("java/util/random/RandomGenerator")));
        assertTrue(forbiddenReferences(model(IoUser.class)).stream()
                .anyMatch(v -> v.contains("java/io/File")));
        assertTrue(forbiddenReferences(model(BannedRecordSignature.class)).stream()
                .anyMatch(v -> v.contains("java/nio/file/Path")));
        assertTrue(forbiddenReferences(model(BannedRecordSignature.class)).stream()
                .anyMatch(v -> v.contains("java/net/URI")));
        assertTrue(forbiddenReferences(model(ArrayAndClassLiteralUser.class)).stream()
                .anyMatch(v -> v.contains("java/io/File")));
    }

    @Test
    void scannerCatchesRandomizedCollectionsAndFloatingPointBytecode() {
        assertTrue(forbiddenReferences(model(ImmutableSetMapOfUser.class)).stream()
                .anyMatch(v -> v.endsWith("-> java/util/Set.of")));
        assertTrue(forbiddenReferences(model(ImmutableSetMapOfUser.class)).stream()
                .anyMatch(v -> v.endsWith("-> java/util/Map.of")));
        assertTrue(forbiddenReferences(model(LocalHashSetUser.class)).stream()
                .anyMatch(v -> v.contains("java/util/HashSet")));
        assertTrue(forbiddenReferences(model(LocalFloatMathUser.class)).stream()
                .anyMatch(v -> v.endsWith("-> double")));
        assertTrue(forbiddenReferences(model(FloatMethodSignatureUser.class)).stream()
                .anyMatch(v -> v.endsWith("-> float")));
    }

    @Test
    void wildsEngineStateRootIsPureDeterministic() {
        assertPureDeterministicStateRoot(WildsEngine.class);
    }

    @Test
    void worldStateRootIsPureDeterministic() {
        assertPureDeterministicStateRoot(WorldState.class);
    }

    @Test
    void stateWalkerCatchesBannedFieldsGenericsSuperclassesAndInterfaces() {
        assertThrows(AssertionError.class, () -> assertPureDeterministicStateRoot(DoubleFieldState.class));
        assertThrows(AssertionError.class, () -> assertPureDeterministicStateRoot(FloatArrayState.class));
        assertThrows(AssertionError.class, () -> assertPureDeterministicStateRoot(BoxedDoubleState.class));
        assertThrows(AssertionError.class, () -> assertPureDeterministicStateRoot(OptionalDoubleState.class));
        assertThrows(AssertionError.class, () -> assertPureDeterministicStateRoot(NumberFieldState.class));
        assertThrows(AssertionError.class, () -> assertPureDeterministicStateRoot(ObjectFieldState.class));
        assertThrows(AssertionError.class, () -> assertPureDeterministicStateRoot(ObjectArrayFieldState.class));
        assertThrows(AssertionError.class, () -> assertPureDeterministicStateRoot(HashMapFieldState.class));
        assertThrows(AssertionError.class, () -> assertPureDeterministicStateRoot(LinkedMapFieldState.class));
        assertThrows(AssertionError.class, () -> assertPureDeterministicStateRoot(InterfaceMapFieldState.class));
        assertThrows(AssertionError.class, () -> assertPureDeterministicStateRoot(InterfaceSetFieldState.class));
        assertThrows(AssertionError.class, () -> assertPureDeterministicStateRoot(SequencedMapFieldState.class));
        assertThrows(AssertionError.class, () -> assertPureDeterministicStateRoot(SequencedSetFieldState.class));
        assertThrows(AssertionError.class, () -> assertPureDeterministicStateRoot(CollectionFieldState.class));
        assertThrows(AssertionError.class, () -> assertPureDeterministicStateRoot(RawGenericFieldState.class));
        assertThrows(AssertionError.class, () -> assertPureDeterministicStateRoot(SeededRngFieldState.class));
        assertThrows(AssertionError.class, () -> assertPureDeterministicStateRoot(SealedDoubleState.class));
        assertThrows(AssertionError.class, () -> assertPureDeterministicStateRoot(SubclassOfDoubleBaseState.class));
        assertThrows(AssertionError.class, () -> assertPureDeterministicStateRoot(SubclassOfGenericDoubleState.class));
        assertThrows(AssertionError.class, () -> assertPureDeterministicStateRoot(NestedGenericDoubleState.class));
        assertThrows(AssertionError.class, () -> assertPureDeterministicStateRoot(UnsealedInterfaceFieldState.class));
        assertThrows(AssertionError.class, () -> assertPureDeterministicStateRoot(ExternalPackageFieldState.class));
    }

    @Test
    void stateWalkerAcceptsCleanState() {
        assertPureDeterministicStateRoot(CleanState.class);
    }

    /** Recursive purity walker over every state type reachable from {@code rootType}. */
    public static void assertPureDeterministicStateRoot(Class<?> rootType) {
        walkStateType(rootType, Map.of(), new HashSet<>(), rootType.getSimpleName(), false);
    }

    private static void walkStateType(Type type, Map<TypeVariable<?>, Type> bindings,
                                      Set<VisitedStateType> visited, String path,
                                      boolean allowParameterizedHead) {
        if (type instanceof TypeVariable<?> tv) {
            Type boundType = bindings.get(tv);
            if (boundType != null) {
                walkStateType(boundType, Map.of(), visited, path, false);
                return;
            }
            for (Type bound : tv.getBounds()) {
                walkStateType(bound, bindings, visited, path + ":" + tv.getName(), false);
            }
            return;
        }
        if (!visited.add(new VisitedStateType(type, bindings))) {
            return;
        }
        if (type instanceof Class<?> clazz) {
            for (Class<?> banned : BANNED_STATE_TYPES) {
                if (banned.isAssignableFrom(clazz)) {
                    throw new AssertionError("Forbidden state type " + clazz.getName() + " at " + path);
                }
            }
            if (!allowParameterizedHead && clazz.getTypeParameters().length > 0) {
                throw new AssertionError("Raw generic state type " + clazz.getName() + " at " + path);
            }
            if (clazz.isArray()) {
                walkStateType(clazz.componentType(), bindings, visited, path + "[]", false);
                return;
            }
            if (clazz.isPrimitive() || clazz.isEnum()) {
                return;
            }
            if (clazz.getName().startsWith("java.")) {
                if (!ALLOWED_JDK_STATE_TYPES.contains(clazz)) {
                    throw new AssertionError("Disallowed JDK state type " + clazz.getName() + " at " + path);
                }
                return;
            }
            if (!clazz.getName().startsWith("com.kotharworks.")) {
                throw new AssertionError("Disallowed external state type " + clazz.getName() + " at " + path);
            }
            if ((clazz.isInterface() || Modifier.isAbstract(clazz.getModifiers())) && !clazz.isSealed()) {
                throw new AssertionError("Unsealed abstract/interface state type "
                        + clazz.getName() + " at " + path);
            }
            Type superType = clazz.getGenericSuperclass();
            if (superType != null && superType != Object.class
                    && superType != Record.class && superType != Enum.class) {
                walkStateType(superType, bindings, visited, path + "^super", false);
            }
            for (Type iface : clazz.getGenericInterfaces()) {
                if (iface instanceof ParameterizedType pt
                        && pt.getRawType() instanceof Class<?> rawIface
                        && rawIface.getName().startsWith("java.")) {
                    for (Type arg : pt.getActualTypeArguments()) {
                        walkStateType(arg, bindings, visited, path + "^iface<>", false);
                    }
                } else if (iface instanceof Class<?> rawIface && rawIface.getName().startsWith("java.")) {
                    // Marker JDK interfaces such as Serializable/Comparable are fine when non-raw or parameterless.
                    if (rawIface.getTypeParameters().length > 0) {
                        throw new AssertionError("Raw generic interface " + rawIface.getName() + " at " + path);
                    }
                } else {
                    walkStateType(iface, bindings, visited, path + "^iface", false);
                }
            }
            for (Field field : clazz.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                if (clazz == EntityTable.class && isEntityTableInternalErasureSlot(field.getName())) {
                    // EntityTable<I, R> erases its backing array to Object[] and holds stateless IdFactory/Function;
                    // concrete <I, R> type arguments are always validated via ParameterizedType.
                    continue;
                }
                walkStateType(field.getGenericType(), bindings, visited, path + "." + field.getName(), false);
            }
            Class<?>[] permitted = clazz.getPermittedSubclasses();
            if (permitted != null) {
                for (Class<?> subclass : permitted) {
                    walkStateType(subclass, bindings, visited, path + "<" + subclass.getSimpleName() + ">", false);
                }
            }
        } else if (type instanceof GenericArrayType array) {
            walkStateType(array.getGenericComponentType(), bindings, visited, path + "[]", false);
        } else if (type instanceof ParameterizedType parameterized) {
            Type rawType = parameterized.getRawType();
            Type[] actualArgs = parameterized.getActualTypeArguments();
            Map<TypeVariable<?>, Type> childBindings = Map.of();
            if (rawType instanceof Class<?> rawClass) {
                TypeVariable<?>[] typeParams = rawClass.getTypeParameters();
                if (typeParams.length == actualArgs.length && typeParams.length > 0) {
                    Map<TypeVariable<?>, Type> resolved = new HashMap<>();
                    for (int i = 0; i < typeParams.length; i++) {
                        resolved.put(typeParams[i], resolveTypeArg(actualArgs[i], bindings));
                    }
                    childBindings = Collections.unmodifiableMap(resolved);
                }
            }
            walkStateType(rawType, childBindings, visited, path, true);
            for (Type argument : actualArgs) {
                walkStateType(argument, bindings, visited, path + "<>", false);
            }
        } else if (type instanceof WildcardType wildcard) {
            for (Type bound : wildcard.getUpperBounds()) {
                walkStateType(bound, bindings, visited, path + "?extends", false);
            }
            for (Type bound : wildcard.getLowerBounds()) {
                walkStateType(bound, bindings, visited, path + "?super", false);
            }
        } else {
            throw new AssertionError("Unsupported reflection Type " + type + " at " + path);
        }
    }

    private static Type resolveTypeArg(Type arg, Map<TypeVariable<?>, Type> bindings) {
        if (arg instanceof TypeVariable<?> tv && bindings.containsKey(tv)) {
            return bindings.get(tv);
        }
        return arg;
    }

    private static boolean isEntityTableInternalErasureSlot(String fieldName) {
        return "rows".equals(fieldName) || "idFactory".equals(fieldName) || "idExtractor".equals(fieldName);
    }

    static List<String> forbiddenReferences(ClassModel model) {
        List<String> violations = new ArrayList<>();
        String className = model.thisClass().asInternalName();
        model.superclass().ifPresent(sc -> checkClassDesc(className, "<class>", sc.asSymbol(), violations));
        for (ClassEntry iface : model.interfaces()) {
            checkClassDesc(className, "<class>", iface.asSymbol(), violations);
        }
        for (FieldModel field : model.fields()) {
            checkClassDesc(className, field.fieldName().stringValue(), field.fieldTypeSymbol(), violations);
        }
        for (MethodModel method : model.methods()) {
            String methodName = method.methodName().stringValue();
            checkMethodTypeDesc(className, methodName, method.methodTypeSymbol(), violations);
            var code = method.code();
            if (code.isEmpty()) {
                continue;
            }
            for (CodeElement element : code.get()) {
                switch (element) {
                    case InvokeInstruction invoke -> {
                        checkMemberRef(className, methodName, invoke.owner().asSymbol(),
                                invoke.name().stringValue(), violations);
                        checkMethodTypeDesc(className, methodName, invoke.typeSymbol(), violations);
                    }
                    case FieldInstruction fieldAccess -> {
                        checkMemberRef(className, methodName, fieldAccess.owner().asSymbol(),
                                fieldAccess.name().stringValue(), violations);
                        checkClassDesc(className, methodName, fieldAccess.typeSymbol(), violations);
                    }
                    case NewObjectInstruction newObject ->
                            checkClassDesc(className, methodName, newObject.className().asSymbol(), violations);
                    case NewReferenceArrayInstruction newRefArray ->
                            checkClassDesc(className, methodName, newRefArray.componentType().asSymbol(), violations);
                    case NewMultiArrayInstruction newMultiArray ->
                            checkClassDesc(className, methodName, newMultiArray.arrayType().asSymbol(), violations);
                    case NewPrimitiveArrayInstruction newPrimArray ->
                            checkTypeKind(className, methodName, newPrimArray.typeKind(), violations);
                    case TypeCheckInstruction typeCheck ->
                            checkClassDesc(className, methodName, typeCheck.type().asSymbol(), violations);
                    case ExceptionCatch exceptionCatch ->
                            exceptionCatch.catchType().ifPresent(
                                    ct -> checkClassDesc(className, methodName, ct.asSymbol(), violations));
                    case ConstantInstruction constant -> {
                        checkTypeKind(className, methodName, constant.typeKind(), violations);
                        if (constant instanceof ConstantInstruction.LoadConstantInstruction ldc) {
                            checkLoadableConstant(className, methodName, ldc.constantEntry(), violations);
                        }
                    }
                    case ConvertInstruction convert -> {
                        checkTypeKind(className, methodName, convert.fromType(), violations);
                        checkTypeKind(className, methodName, convert.toType(), violations);
                    }
                    case OperatorInstruction operator ->
                            checkTypeKind(className, methodName, operator.typeKind(), violations);
                    case InvokeDynamicInstruction indy -> {
                        checkMethodTypeDesc(className, methodName, indy.typeSymbol(), violations);
                        checkBootstrap(className, methodName, indy.invokedynamic().bootstrap(), violations);
                    }
                    default -> {
                    }
                }
            }
        }
        return violations;
    }

    private static void checkBootstrap(String className, String methodName,
                                       BootstrapMethodEntry bootstrapEntry, List<String> violations) {
        checkMethodHandle(className, methodName, bootstrapEntry.bootstrapMethod(), violations);
        for (LoadableConstantEntry argument : bootstrapEntry.arguments()) {
            checkLoadableConstant(className, methodName, argument, violations);
        }
    }

    private static void checkLoadableConstant(String className, String methodName,
                                              LoadableConstantEntry entry, List<String> violations) {
        switch (entry) {
            case ClassEntry ce -> checkClassDesc(className, methodName, ce.asSymbol(), violations);
            case MethodHandleEntry handle -> checkMethodHandle(className, methodName, handle, violations);
            case MethodTypeEntry mte -> checkMethodTypeDesc(className, methodName, mte.asSymbol(), violations);
            case ConstantDynamicEntry condy -> {
                checkClassDesc(className, methodName, condy.typeSymbol(), violations);
                checkBootstrap(className, methodName, condy.bootstrap(), violations);
            }
            default -> {
            }
        }
    }

    private static void checkMethodHandle(String className, String methodName,
                                          MethodHandleEntry handle, List<String> violations) {
        MemberRefEntry ref = handle.reference();
        checkMemberRef(className, methodName, ref.owner().asSymbol(), ref.name().stringValue(), violations);
        checkMethodTypeDesc(className, methodName, handle.asSymbol().invocationType(), violations);
    }

    private static void checkMethodTypeDesc(String className, String context,
                                            MethodTypeDesc methodType, List<String> violations) {
        checkClassDesc(className, context, methodType.returnType(), violations);
        for (ClassDesc param : methodType.parameterList()) {
            checkClassDesc(className, context, param, violations);
        }
    }

    private static void checkClassDesc(String className, String context,
                                       ClassDesc desc, List<String> violations) {
        ClassDesc leaf = unwrapArray(desc);
        if (leaf.isPrimitive()) {
            String d = leaf.descriptorString();
            if ("F".equals(d)) {
                violations.add(className + "." + context + " -> float");
            } else if ("D".equals(d)) {
                violations.add(className + "." + context + " -> double");
            }
            return;
        }
        if (leaf.isClassOrInterface()) {
            String ds = leaf.descriptorString();
            String internalName = ds.substring(1, ds.length() - 1);
            if (bannedType(internalName)) {
                violations.add(className + "." + context + " -> " + internalName);
            }
        }
    }

    private static void checkMemberRef(String className, String methodName, ClassDesc ownerDesc,
                                       String member, List<String> violations) {
        checkClassDesc(className, methodName, ownerDesc, violations);
        ClassDesc leaf = unwrapArray(ownerDesc);
        if (leaf.isClassOrInterface()) {
            String ds = leaf.descriptorString();
            String owner = ds.substring(1, ds.length() - 1);
            if (BANNED_MEMBERS.contains(owner + "." + member) || bannedType(owner)) {
                violations.add(className + "." + methodName + " -> " + owner + "." + member);
            }
        }
    }

    private static void checkTypeKind(String className, String methodName,
                                      TypeKind kind, List<String> violations) {
        if (kind == TypeKind.FLOAT) {
            violations.add(className + "." + methodName + " -> float");
        } else if (kind == TypeKind.DOUBLE) {
            violations.add(className + "." + methodName + " -> double");
        }
    }

    private static ClassDesc unwrapArray(ClassDesc desc) {
        ClassDesc cur = desc;
        while (cur.isArray()) {
            cur = cur.componentType();
        }
        return cur;
    }

    private static boolean bannedType(String internalName) {
        return BANNED_TYPES.contains(internalName)
                || BANNED_PREFIXES.stream().anyMatch(internalName::startsWith);
    }

    private static Path productionClassesDir() {
        try {
            return Path.of(WildsEngine.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("cannot resolve WildsEngine code source", e);
        }
    }

    private static ClassModel model(Class<?> type) {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream in = type.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing class resource " + resource);
            }
            return ClassFile.of().parse(in.readAllBytes());
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + resource, e);
        }
    }

    static final class WallClockUser {
        long wall() {
            return System.currentTimeMillis();
        }
    }

    static final class NanoClockUser {
        long nano() {
            return System.nanoTime();
        }
    }

    static final class IdentityHashUser {
        int id(Object o) {
            return System.identityHashCode(o);
        }
    }

    static final class RandomUuidUser {
        UUID uuid() {
            return UUID.randomUUID();
        }
    }

    static final class ClockRefUser {
        final Supplier<Long> clock = System::currentTimeMillis;
    }

    static final class RandomRefUser {
        final Supplier<Double> random = Math::random;
    }

    static final class StaticInitClockUser {
        static final long BOOT = System.currentTimeMillis();
    }

    interface DefaultMethodClockInterface {
        default long now() {
            return System.nanoTime();
        }
    }

    interface AbstractForbiddenSignatureInterface {
        Instant instant();
    }

    static final class UnseededRandomUser {
        Random make() {
            return new Random();
        }
    }

    static final class RandomGeneratorUser {
        long sample() {
            return RandomGenerator.getDefault().nextLong();
        }
    }

    static final class IoUser {
        File file() {
            return new File("forbidden");
        }
    }

    record BannedRecordSignature(Path path, URI uri) {}

    static final class ArrayAndClassLiteralUser {
        Object[] refs() {
            File[][] multi = new File[1][1];
            return new Object[] { new File[2], multi, File.class };
        }
    }

    static final class ImmutableSetMapOfUser {
        Object randomized() {
            return List.of(Set.of("a", "b"), Map.of("a", 1, "b", 2));
        }
    }

    static final class LocalHashSetUser {
        int count() {
            Set<String> s = new HashSet<>();
            s.add("x");
            return s.size();
        }
    }

    static final class LocalFloatMathUser {
        long scaled(long x) {
            return (long) (x * 1.35);
        }
    }

    static final class FloatMethodSignatureUser {
        float ratio(int a) {
            return a;
        }
    }

    static final class DoubleFieldState {
        double x;
    }

    static final class FloatArrayState {
        float[] xs;
    }

    static final class BoxedDoubleState {
        List<Double> xs;
    }

    static final class OptionalDoubleState {
        OptionalDouble maybe;
    }

    static final class NumberFieldState {
        Number n;
    }

    static final class ObjectFieldState {
        Object any;
    }

    static final class ObjectArrayFieldState {
        Object[] any;
    }

    static final class HashMapFieldState {
        HashMap<String, String> m;
    }

    static final class LinkedMapFieldState {
        LinkedHashMap<String, String> m;
    }

    static final class InterfaceMapFieldState {
        Map<String, String> m;
    }

    static final class InterfaceSetFieldState {
        Set<String> s;
    }

    static final class SequencedMapFieldState {
        SequencedMap<String, String> m;
    }

    static final class SequencedSetFieldState {
        SequencedSet<String> s;
    }

    static final class CollectionFieldState {
        Collection<String> c;
    }

    @SuppressWarnings("rawtypes")
    static final class RawGenericFieldState {
        List raw;
    }

    static final class SeededRngFieldState {
        SeededRng rng;
    }

    static sealed interface Shape permits RoundShape, SquareShape {}

    static record RoundShape(double radius) implements Shape {}

    static record SquareShape(long side) implements Shape {}

    static final class SealedDoubleState {
        List<Shape> shapes;
    }

    static sealed class DoubleBase permits SubclassOfDoubleBaseState {
        double hidden;
    }

    static final class SubclassOfDoubleBaseState extends DoubleBase {
        int visible;
    }

    static sealed class GenericBase<T> permits SubclassOfGenericDoubleState {
        T item;
    }

    static final class SubclassOfGenericDoubleState extends GenericBase<Double> {}

    static record InnerBox<B>(B value) {}

    static record OuterBox<A>(InnerBox<A> inner) {}

    static final class NestedGenericDoubleState {
        OuterBox<Double> outer;
    }

    interface OpenStrategy {
        long compute();
    }

    static final class UnsealedInterfaceFieldState {
        OpenStrategy strategy;
    }

    static final class ExternalPackageFieldState {
        Cipher cipher;
    }

    static sealed interface CleanTag permits CleanTagA, CleanTagB {}

    static record CleanTagA(int code) implements CleanTag {}

    static record CleanTagB(String label) implements CleanTag {}

    static final class CleanState {
        long id;
        String name;
        List<Long> xs;
        TreeMap<String, Long> sorted;
        TreeSet<String> tags;
        OuterBox<Long> nestedBox;
        CleanTag tag;
        int[] dense;
    }
}
