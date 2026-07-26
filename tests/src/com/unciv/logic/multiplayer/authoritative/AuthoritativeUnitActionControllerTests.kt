package com.unciv.logic.multiplayer.authoritative

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class AuthoritativeUnitActionControllerTests {
    @Test
    fun exactProjectedSpecialActionsRouteToTypedOperations() = runBlocking {
        val initial = gameProjectionWithAllActions()
        val calls = mutableListOf<String>()
        val actions = AuthoritativeUnitActions(
            useReligiousAction = { unit, action ->
                calls += "religion:$unit:$action"
                AuthoritativeCommandOutcome.RetryRequired
            },
            useGreatPersonAction = { unit, action ->
                calls += "great:$unit:$action"
                AuthoritativeCommandOutcome.RetryRequired
            },
            gift = { unit ->
                calls += "gift:$unit"
                AuthoritativeCommandOutcome.RetryRequired
            },
            addToCapitalProject = { unit ->
                calls += "project:$unit"
                AuthoritativeCommandOutcome.RetryRequired
            },
            transform = { unit, action ->
                calls += "transform:$unit:$action"
                AuthoritativeCommandOutcome.RetryRequired
            },
            triggerUnique = { unit, action ->
                calls += "trigger:$unit:$action"
                AuthoritativeCommandOutcome.RetryRequired
            },
            createInstantImprovement = { unit, action ->
                calls += "improvement:$unit:$action"
                AuthoritativeCommandOutcome.RetryRequired
            },
        )
        val unitActions = controller(initial, actions).unitActions

        unitActions.useReligiousAction(17, ReligiousUnitAction.SpreadReligion)
        unitActions.useGreatPersonAction(17, GreatPersonUnitAction.HurryResearch)
        unitActions.gift(17)
        unitActions.addToCapitalProject(17)
        unitActions.transform(17, "transform-1")
        unitActions.triggerUnique(17, "trigger-1")
        unitActions.createInstantImprovement(17, "improvement-1")

        assertEquals(listOf(
            "religion:17:${ReligiousUnitAction.SpreadReligion}",
            "great:17:${GreatPersonUnitAction.HurryResearch}",
            "gift:17",
            "project:17",
            "transform:17:transform-1",
            "trigger:17:trigger-1",
            "improvement:17:improvement-1",
        ), calls)
    }

    @Test
    fun inventedOrUnadvertisedActionsNeverInvokeTransport() = runBlocking {
        val initial = gameProjectionWithAllActions()
        var calls = 0
        val unitActions = controller(
            initial,
            AuthoritativeUnitActions.Unavailable.copy(
                transform = { _, _ ->
                    calls++
                    AuthoritativeCommandOutcome.RetryRequired
                },
            ),
        ).unitActions

        assertThrows<IllegalArgumentException> {
            unitActions.transform(17, "invented")
        }
        assertThrows<IllegalStateException> {
            unitActions.triggerUnique(999, "trigger-1")
        }
        assertThrows<IllegalArgumentException> {
            unitActions.useReligiousAction(17, ReligiousUnitAction.RemoveHeresy)
        }
        assertThrows<IllegalArgumentException> {
            unitActions.useGreatPersonAction(17, GreatPersonUnitAction.HurryPolicy)
        }
        assertEquals(0, calls)
    }

    @Test
    fun uncertainSpecialActionRetryKeepsProjection() = runBlocking {
        val initial = gameProjectionWithAllActions()
        var calls = 0
        val world = controller(
            initial,
            AuthoritativeUnitActions.Unavailable.copy(
                transform = { _, _ ->
                    calls++
                    AuthoritativeCommandOutcome.RetryRequired
                },
            ),
        )

        world.unitActions.transform(17, "transform-1")
        assertEquals(AuthoritativeWorldStatus.RetryRequired, world.status)
        assertEquals(7, world.current.committedRevision)
        world.unitActions.transform(17, "transform-1")
        assertEquals(2, calls)
        assertEquals(7, world.current.committedRevision)
    }

    private fun controller(
        initial: ApiV3GameProjection,
        actions: AuthoritativeUnitActions,
    ) = AuthoritativeWorldController(
        initial = initial,
        refreshProjection = { initial },
        moveUnit = { _, _, _ -> AuthoritativeCommandOutcome.Rejected("test") },
        endTurn = { AuthoritativeCommandOutcome.Rejected("test") },
        unitActionActions = actions,
    )

    private fun gameProjectionWithAllActions(): ApiV3GameProjection {
        val projection = Json {
            ignoreUnknownKeys = false
            encodeDefaults = true
        }.decodeFromString(
            PlayerProjection.serializer(),
            projectionFixture().readText(),
        )
        val unit = projection.ownUnits.single { it.id == 17 }.copy(
            availableGreatPersonActions = listOf(GreatPersonUnitAction.HurryResearch),
            canGift = true,
            capitalProjectName = "The Spaceship",
            availableInstantImprovementActions = listOf(
                ProjectedInstantImprovementAction("improvement-1", "Create Academy"),
            ),
            availableTransformActions = listOf(
                ProjectedUnitTransformAction("transform-1", "Infantry"),
            ),
            availableTriggerActions = listOf(
                ProjectedUnitTriggerAction("trigger-1", "Trigger unique"),
            ),
        )
        return ApiV3GameProjection(
            gameId = "game-a",
            projectionVersion = PlayerProjection.CURRENT_PROJECTION_VERSION,
            committedRevision = 7,
            canonicalStateHash = "hash-7",
            projectionHash = "projection-7",
            projection = projection.copy(
                ownUnits = projection.ownUnits.map {
                    if (it.id == unit.id) unit else it
                },
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
}
