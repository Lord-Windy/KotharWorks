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
package com.kotharworks.wilds.headless;

import com.kotharworks.wilds.WildsEngine;
import com.kotharworks.wilds.observe.WorldView;
import com.kotharworks.wilds.seed.WorldSeed;
import com.kotharworks.wilds.state.PlaceEdge;
import com.kotharworks.wilds.state.PlaceRow;
import com.kotharworks.wilds.worldgen.DescriptionComposer;
import com.kotharworks.wilds.worldgen.DescriptionComposer.InspectTarget;
import com.kotharworks.wilds.worldgen.StubDescriptionComposer;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * {@code inspect --seed <long|0xHex>}: builds the seeded bootstrap world and prints its
 * {@code WorldTopologyBootstrap} place graph — every place with kind, label, and outgoing
 * edge travel times — plus the {@code GLANCE}/{@code LOOK}/{@code EXAMINE} stub
 * descriptions composed through the {@code DescriptionComposer} seam.
 */
final class InspectCommand {

    private InspectCommand() {
    }

    static int execute(String[] args, PrintWriter out, PrintWriter err) {
        String seedRaw = null;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--seed" -> {
                        if (i + 1 >= args.length) {
                            throw new IllegalArgumentException("missing value for --seed");
                        }
                        seedRaw = args[++i];
                    }
                    default -> throw new IllegalArgumentException(
                            "unknown inspect flag \"" + args[i] + "\"");
                }
            }
            if (seedRaw == null) {
                throw new IllegalArgumentException("missing required flag --seed");
            }
            long seed = HeadlessMain.parseSeed(seedRaw);
            return inspect(seed, out);
        } catch (IllegalArgumentException badArgs) {
            err.println("inspect: " + badArgs.getMessage());
            return HeadlessMain.EXIT_USAGE;
        }
    }

    private static int inspect(long seed, PrintWriter out) {
        WildsEngine engine = new WildsEngine(new WorldSeed(seed));
        WorldView view = engine.view();
        StubDescriptionComposer composer = StubDescriptionComposer.INSTANCE;

        out.println("inspect: seed=" + HeadlessMain.hex16(view.seed().value())
                + " rulesVersion=" + view.rulesVersion()
                + " engineVersion=" + WildsEngine.API_VERSION);

        List<PlaceRow> places = new ArrayList<>(view.places());
        places.sort(Comparator.comparing(place -> place.id().toWire()));
        int edgeCount = 0;
        for (PlaceRow place : places) {
            out.println("place [" + place.id().toWire() + "] " + place.label()
                    + " (" + place.kindId().slug() + ")");
            for (PlaceEdge edge : place.edges()) {
                out.println("  edge -> " + edge.to().toWire()
                        + " (" + edge.travelDurationMs() + "ms)");
                edgeCount++;
            }
            for (DescriptionComposer.ZoomLevel zoom : DescriptionComposer.ZoomLevel.values()) {
                String description = composer.compose(view,
                        new InspectTarget.PlaceTarget(place.id()), zoom);
                out.println("  " + zoom.name().toLowerCase(Locale.ROOT) + ": " + description);
            }
        }
        out.println("places=" + places.size() + " edges=" + edgeCount);
        return HeadlessMain.EXIT_OK;
    }
}
