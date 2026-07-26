package com.unciv.logic.multiplayer.authoritative

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class AuthoritativePromptControllerTests {
    @Test
    fun exactProjectedVotesSelectionsEventsAndDiplomacyRoute() = runBlocking {
        val initial = gameProjectionWithPrompts()
        val calls = mutableListOf<String>()
        val actions = AuthoritativePromptActions(
            castDiplomaticVote = { candidate ->
                calls += "vote:${candidate ?: "abstain"}"
                AuthoritativeCommandOutcome.RetryRequired
            },
            chooseGreatPerson = { unit ->
                calls += "great:$unit"
                AuthoritativeCommandOutcome.RetryRequired
            },
            resolveEvent = { prompt, choice ->
                calls += "event:$prompt:$choice"
                AuthoritativeCommandOutcome.RetryRequired
            },
            respondToDiplomacy = { prompt, accept ->
                calls += "diplomacy:$prompt:$accept"
                AuthoritativeCommandOutcome.RetryRequired
            },
            respondToCityState = { prompt, response ->
                calls += "city-state:$prompt:$response"
                AuthoritativeCommandOutcome.RetryRequired
            },
        )
        val prompts = controller(initial, actions).prompts

        prompts.castDiplomaticVote(null)
        prompts.castDiplomaticVote("Greece")
        prompts.chooseGreatPerson("Great Engineer")
        prompts.resolveEvent(EVENT_PROMPT_ID, "event-choice")
        prompts.respondToDiplomacy(FRIENDSHIP_PROMPT_ID, true)
        prompts.respondToCityState(
            CITY_STATE_PROMPT_ID,
            CityStateProtectionResponse.Condemn,
        )

        assertEquals(listOf(
            "vote:abstain",
            "vote:Greece",
            "great:Great Engineer",
            "event:$EVENT_PROMPT_ID:event-choice",
            "diplomacy:$FRIENDSHIP_PROMPT_ID:true",
            "city-state:$CITY_STATE_PROMPT_ID:${CityStateProtectionResponse.Condemn}",
        ), calls)
    }

    @Test
    fun inventedAndWrongPromptResponseKindsNeverInvokeTransport() = runBlocking {
        val initial = gameProjectionWithPrompts()
        var calls = 0
        val actions = AuthoritativePromptActions.Unavailable.copy(
            resolveEvent = { _, _ ->
                calls++
                AuthoritativeCommandOutcome.RetryRequired
            },
        )
        val prompts = controller(initial, actions).prompts

        assertThrows<IllegalArgumentException> {
            prompts.castDiplomaticVote("Unknown")
        }
        assertThrows<IllegalArgumentException> {
            prompts.chooseGreatPerson("Invented")
        }
        assertThrows<IllegalArgumentException> {
            prompts.resolveEvent(EVENT_PROMPT_ID, "invented")
        }
        assertThrows<IllegalArgumentException> {
            prompts.respondToDiplomacy(CITY_STATE_PROMPT_ID, true)
        }
        assertThrows<IllegalArgumentException> {
            prompts.respondToCityState(
                CITY_STATE_PROMPT_ID,
                CityStateProtectionResponse.DeclareWar,
            )
        }
        assertEquals(0, calls)
    }

    @Test
    fun uncertainPromptRetryKeepsProjection() = runBlocking {
        val initial = gameProjectionWithPrompts()
        var calls = 0
        val world = controller(
            initial,
            AuthoritativePromptActions.Unavailable.copy(
                chooseGreatPerson = {
                    calls++
                    AuthoritativeCommandOutcome.RetryRequired
                },
            ),
        )

        world.prompts.chooseGreatPerson("Great Engineer")
        assertEquals(AuthoritativeWorldStatus.RetryRequired, world.status)
        assertEquals(7, world.current.committedRevision)
        world.prompts.chooseGreatPerson("Great Engineer")
        assertEquals(2, calls)
        assertEquals(7, world.current.committedRevision)
    }

    private fun controller(
        initial: ApiV3GameProjection,
        actions: AuthoritativePromptActions,
    ) = AuthoritativeWorldController(
        initial = initial,
        refreshProjection = { initial },
        moveUnit = { _, _, _ -> AuthoritativeCommandOutcome.Rejected("test") },
        endTurn = { AuthoritativeCommandOutcome.Rejected("test") },
        promptActions = actions,
    )

    private fun gameProjectionWithPrompts(): ApiV3GameProjection {
        val projection = Json {
            ignoreUnknownKeys = false
            encodeDefaults = true
        }.decodeFromString(
            PlayerProjection.serializer(),
            projectionFixture().readText(),
        )
        return ApiV3GameProjection(
            gameId = "game-a",
            projectionVersion = PlayerProjection.CURRENT_PROJECTION_VERSION,
            committedRevision = 7,
            canonicalStateHash = "hash-7",
            projectionHash = "projection-7",
            projection = projection.copy(
                diplomacyPrompts = projection.diplomacyPrompts + ProjectedDiplomacyPrompt(
                    promptId = CITY_STATE_PROMPT_ID,
                    requestingCivilizationId = "Greece",
                    type = DiplomacyPromptType.BulliedProtectedMinor,
                    demand = null,
                    cityStateCivilizationId = "Geneva",
                    availableCityStateResponses = listOf(
                        CityStateProtectionResponse.Condemn,
                    ),
                ),
                eventPrompts = listOf(ProjectedEventPrompt(
                    promptId = EVENT_PROMPT_ID,
                    eventName = "A choice",
                    unitId = 17,
                    text = "Choose carefully",
                    choices = listOf(ProjectedEventChoice("event-choice", "Proceed")),
                )),
            ),
        )
    }

    private fun projectionFixture(): File = generateSequence(
        File(System.getProperty("user.dir")).absoluteFile,
        File::getParentFile,
    ).map { File(it, "protocol/player-projection-v58.fixture.json") }
        .first { it.isFile }

    private suspend inline fun <reified T : Throwable> assertThrows(
        crossinline block: suspend () -> Unit,
    ): T {
        try {
            block()
        } catch (throwable: Throwable) {
            if (throwable is T) return throwable
            throw AssertionError("Expected ${T::class.simpleName}, got $throwable", throwable)
        }
        throw AssertionError("Expected ${T::class.simpleName}")
    }

    companion object {
        private const val FRIENDSHIP_PROMPT_ID =
            "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
        private const val CITY_STATE_PROMPT_ID =
            "1111111111111111111111111111111111111111111111111111111111111111"
        private const val EVENT_PROMPT_ID =
            "2222222222222222222222222222222222222222222222222222222222222222"
    }
}
