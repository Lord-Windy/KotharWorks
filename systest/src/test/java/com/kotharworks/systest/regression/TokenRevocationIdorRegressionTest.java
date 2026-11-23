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
package com.kotharworks.systest.regression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.kotharworks.systest.support.Api;
import com.kotharworks.systest.support.Persona;
import com.kotharworks.systest.support.World;

/**
 * Regression for commit 196992f (Pass 07 review): token revocation IDOR —
 * one researcher must not be able to revoke (or probe) another researcher's
 * bot tokens by guessing seat/token ids.
 */
@Tag("regression")
class TokenRevocationIdorRegressionTest {

    @Test
    @DisplayName("196992f: a researcher cannot revoke another account's bot token")
    void cannotRevokeForeignToken() {
        World.BotSeat adminBot = World.botSeat(Persona.ADMIN, "regression-admin-bot");
        Api sam = Api.login(Persona.SAM);

        Api.Resp direct = sam.revokeToken(adminBot.seatId(), adminBot.tokenId());
        assertTrue(direct.status() == 403 || direct.status() == 404, direct::toString);

        World.BotSeat samBot = World.botSeat(Persona.SAM, "regression-sam-bot");
        Api.Resp crossed = sam.revokeToken(samBot.seatId(), adminBot.tokenId());
        assertEquals(404, crossed.status(), "token id must be scoped to the seat: " + crossed);

        Api.bot(adminBot.token()).observation().expect(200);
    }
}
