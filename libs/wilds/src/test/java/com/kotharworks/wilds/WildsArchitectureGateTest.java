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
import com.kotharworks.wilds.state.WorldState;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.constantpool.LoadableConstantEntry;
import java.lang.classfile.constantpool.MethodHandleEntry;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.classfile.instruction.TypeCheckInstruction;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JDK 25 bytecode &amp; reflection gate keeping {@code libs/wilds} pure and deterministic.
 */
class WildsArchitectureGateTest {

    private static final List<String> BANNED_MEMBERS = List.of(
            "java/lang/System.currentTimeMillis",
            "java/lang/System.nanoTime",
            "java/lang/Math.random");

    private static final List<String> BANNED_TYPES = List.of(
            "java/time/Instant", "java/time/Clock", "java/time/LocalDateTime", "java/time/ZonedDateTime",
            "java/util/Date", "java/util/Timer",
            "java/util/Random", "java/util/concurrent/ThreadLocalRandom",
            "java/security/SecureRandom", "java/util/SplittableRandom",
            "java/io/File", "java/io/FileInputStream", "java/io/FileOutputStream",
            "java/io/FileReader", "java/io/FileWriter", "java/io/RandomAccessFile");

    private static final List<String> BANNED_PREFIXES = List.of(
            "java/nio/file/", "java/net/", "java/sql/", "javax/sql/", "com/kotharworks/auth/");

    private static final List<Class<?>> BANNED_STATE_TYPES = List.of(
            float.class, double.class, Float.class, Double.class,
            HashMap.class, HashSet.class, IdentityHashMap.class, ConcurrentHashMap.class,
            SeededRng.class);

    @Test
    void allProductionClassesAreFreeOfForbiddenReferences() throws IOException {
        Path classesDir = Path.of("target", "classes");
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
    void scannerCatchesWallClockAndNanoCalls() {
        assertTrue(forbiddenReferences(model(WallClockUser.class)).stream()
                .anyMatch(v -> v.endsWith("-> java/lang/System.currentTimeMillis")));
        assertTrue(forbiddenReferences(model(NanoClockUser.class)).stream()
                .anyMatch(v -> v.endsWith("-> java/lang/System.nanoTime")));
    }

    @Test
    void scannerCatchesMethodReferences() {
        assertTrue(forbiddenReferences(model(ClockRefUser.class)).stream()
                .anyMatch(v -> v.endsWith("-> java/lang/System.currentTimeMillis")));
        assertTrue(forbiddenReferences(model(RandomRefUser.class)).stream()
                .anyMatch(v -> v.endsWith("-> java/lang/Math.random")));
    }

    @Test
    void scannerCatchesUnseededRandomAndIo() {
        assertTrue(forbiddenReferences(model(UnseededRandomUser.class)).stream()
                .anyMatch(v -> v.contains("java/util/Random")));
        assertTrue(forbiddenReferences(model(IoUser.class)).stream()
                .anyMatch(v -> v.contains("java/io/File")));
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
    void stateWalkerCatchesBannedFields() {
        assertThrows(AssertionError.class, () -> assertPureDeterministicStateRoot(DoubleFieldState.class));
        assertThrows(AssertionError.class, () -> assertPureDeterministicStateRoot(FloatArrayState.class));
        assertThrows(AssertionError.class, () -> assertPureDeterministicStateRoot(HashMapFieldState.class));
        assertThrows(AssertionError.class, () -> assertPureDeterministicStateRoot(LinkedMapFieldState.class));
        assertThrows(AssertionError.class, () -> assertPureDeterministicStateRoot(BoxedDoubleState.class));
        assertThrows(AssertionError.class, () -> assertPureDeterministicStateRoot(SeededRngFieldState.class));
        assertThrows(AssertionError.class, () -> assertPureDeterministicStateRoot(SealedDoubleState.class));
    }

    @Test
    void stateWalkerAcceptsCleanState() {
        assertPureDeterministicStateRoot(CleanState.class);
    }

    /** Recursive purity walker over every state type reachable from {@code rootType}. */
    public static void assertPureDeterministicStateRoot(Class<?> rootType) {
        walkStateType(rootType, new HashSet<>(), rootType.getSimpleName());
    }

    private static void walkStateType(Type type, Set<Type> visited, String path) {
        if (!visited.add(type)) {
            return;
        }
        if (type instanceof Class<?> clazz) {
            for (Class<?> banned : BANNED_STATE_TYPES) {
                if (banned.isAssignableFrom(clazz)) {
                    throw new AssertionError("Forbidden state type " + clazz.getName() + " at " + path);
                }
            }
            if (clazz.isArray()) {
                walkStateType(clazz.componentType(), visited, path + "[]");
                return;
            }
            if (clazz.getName().startsWith("java.")) {
                return;
            }
            if (clazz.getName().startsWith("com.kotharworks.")) {
                for (Field field : clazz.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    walkStateType(field.getGenericType(), visited, path + "." + field.getName());
                }
                Class<?>[] permitted = clazz.getPermittedSubclasses();
                if (permitted != null) {
                    for (Class<?> subclass : permitted) {
                        walkStateType(subclass, visited, path + "<" + subclass.getSimpleName() + ">");
                    }
                }
            }
        } else if (type instanceof GenericArrayType array) {
            walkStateType(array.getGenericComponentType(), visited, path + "[]");
        } else if (type instanceof ParameterizedType parameterized) {
            walkStateType(parameterized.getRawType(), visited, path);
            for (Type argument : parameterized.getActualTypeArguments()) {
                walkStateType(argument, visited, path + "<>");
            }
        } else if (type instanceof WildcardType wildcard) {
            for (Type bound : wildcard.getUpperBounds()) {
                walkStateType(bound, visited, path + "?extends");
            }
            for (Type bound : wildcard.getLowerBounds()) {
                walkStateType(bound, visited, path + "?super");
            }
        }
    }

    static List<String> forbiddenReferences(ClassModel model) {
        List<String> violations = new ArrayList<>();
        String className = model.thisClass().asInternalName();
        for (MethodModel method : model.methods()) {
            String methodName = method.methodName().stringValue();
            var code = method.code();
            if (code.isEmpty()) {
                continue;
            }
            for (CodeElement element : code.get()) {
                if (element instanceof InvokeInstruction invoke) {
                    checkMember(className, methodName, invoke.owner().asInternalName(),
                            invoke.name().stringValue(), violations);
                } else if (element instanceof FieldInstruction fieldAccess) {
                    checkType(className, methodName, fieldAccess.owner().asInternalName(), violations);
                } else if (element instanceof NewObjectInstruction newObject) {
                    checkType(className, methodName, newObject.className().asInternalName(), violations);
                } else if (element instanceof TypeCheckInstruction typeCheck) {
                    checkType(className, methodName, typeCheck.type().asInternalName(), violations);
                } else if (element instanceof InvokeDynamicInstruction indy) {
                    MethodHandleEntry bootstrap = indy.invokedynamic().bootstrap().bootstrapMethod();
                    checkMember(className, methodName, bootstrap.reference().owner().asInternalName(),
                            bootstrap.reference().nameAndType().name().stringValue(), violations);
                    for (LoadableConstantEntry argument : indy.invokedynamic().bootstrap().arguments()) {
                        if (argument instanceof MethodHandleEntry handle) {
                            checkMember(className, methodName, handle.reference().owner().asInternalName(),
                                    handle.reference().nameAndType().name().stringValue(), violations);
                        }
                    }
                }
            }
        }
        return violations;
    }

    private static void checkMember(String className, String methodName, String owner, String member,
                                    List<String> violations) {
        if (BANNED_MEMBERS.contains(owner + "." + member) || bannedType(owner)) {
            violations.add(className + "." + methodName + " -> " + owner + "." + member);
        }
    }

    private static void checkType(String className, String methodName, String owner, List<String> violations) {
        if (bannedType(owner)) {
            violations.add(className + "." + methodName + " -> " + owner);
        }
    }

    private static boolean bannedType(String internalName) {
        return BANNED_TYPES.contains(internalName)
                || BANNED_PREFIXES.stream().anyMatch(internalName::startsWith);
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

    static final class ClockRefUser {
        final Supplier<Long> clock = System::currentTimeMillis;
    }

    static final class RandomRefUser {
        final Supplier<Double> random = Math::random;
    }

    static final class UnseededRandomUser {
        java.util.Random make() {
            return new java.util.Random();
        }
    }

    static final class IoUser {
        java.io.File file() {
            return new java.io.File("forbidden");
        }
    }

    static final class DoubleFieldState {
        double x;
    }

    static final class FloatArrayState {
        float[] xs;
    }

    static final class HashMapFieldState {
        HashMap<String, String> m;
    }

    static final class LinkedMapFieldState {
        java.util.LinkedHashMap<String, String> m;
    }

    static final class BoxedDoubleState {
        List<Double> xs;
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

    static final class CleanState {
        long id;
        String name;
        List<Long> xs;
        TreeMap<String, Long> sorted;
        int[] dense;
    }
}
