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
package com.kotharworks.wilds.server;

import com.kotharworks.wilds.WildsEngine;
import com.kotharworks.wilds.command.WorldCommand;
import com.kotharworks.wilds.seed.WorldSeed;

public final class Main {

    private Main() {}

    public static String banner(long seed) {
        var engine = new WildsEngine(new WorldSeed(seed));
        engine.apply(new WorldCommand.RegisterOwner(1L, "Wanderer", "Scout"));
        return "KotharWorks :: Ironwood Wilds — scaffold world " + engine.worldHash()
                + " (API v" + WildsEngine.API_VERSION + ")";
    }

    public static void main(String[] args) {
        System.out.println(banner(1L));
    }
}
