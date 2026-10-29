package ca.mrklar.outlander.protocol

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SmokeTest {

    @Test
    fun addition() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun coroutineRunTest() = runTest {
        val deferred = async { 2 + 2 }
        assertEquals(4, deferred.await())
    }
}
