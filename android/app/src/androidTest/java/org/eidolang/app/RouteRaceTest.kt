package org.eidolang.app

import org.eidolang.feature.home.firstAnswer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A poll has to end, and it has to end with the first answer rather than the last failure.
 *
 * Both halves were real. Trying the routes in order meant a poll paid for every route that was
 * going to fail — nine minutes of onion retries before the locker, which answers in under a second,
 * was asked at all. Racing them fixed that but not the empty case: with nothing anywhere, the race
 * still waited for the slowest way of finding nothing, and the screen sat on "Проверяю" for
 * minutes.
 */
class RouteRaceTest {

    @Test
    fun theFirstAnswerWinsAndTheSlowLosersDoNotHoldItUp() {
        val started = System.currentTimeMillis()
        val answer = firstAnswer<String>(
            20_000,
            { Thread.sleep(30_000); "медленный" },
            { Thread.sleep(300); "быстрый" },
            { Thread.sleep(30_000); "тоже медленный" },
        )
        val took = System.currentTimeMillis() - started
        assertEquals("взят не первый ответивший", "быстрый", answer)
        assertTrue("быстрый ответ ждал медленных: ${took}мс", took < 5_000)
        println("RACE PASS первый ответ забран за ${took}мс, медленные не задержали")
    }

    @Test
    fun aRaceThatFindsNothingGivesUpOnTime() {
        val started = System.currentTimeMillis()
        val answer = firstAnswer<String>(
            3_000,
            { Thread.sleep(60_000); "никогда" },
            { Thread.sleep(60_000); "тоже никогда" },
        )
        val took = System.currentTimeMillis() - started
        assertNull("из ниоткуда пришёл ответ", answer)
        // The bound is what stops "Проверяю" hanging; without it this waits a full minute.
        assertTrue("гонка не уложилась в свой предел: ${took}мс", took < 10_000)
        println("RACE PASS пустая гонка сдалась за ${took}мс вместо минуты")
    }

    @Test
    fun anEmptyRouteIsNotAnAnswer() {
        assertNull(
            "нулевой маршрут выдан за ответ",
            firstAnswer<String>(2_000, { null }, { null }),
        )
        println("RACE PASS отсутствие ответа не выдаётся за ответ")
    }
}
