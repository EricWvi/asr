package top.onlyquant.tingxie.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

class SendsTest {
    private fun mac(id: Long, online: Boolean, active: Long = 0) =
        Device(id, "m$id", "mac", 0, 0, online, active)

    private fun rec(id: Long, state: SendState = SendState.Sent) = SendRecord(id, "t$id", state)

    @Test fun autoPicksTheOnlineMacUsedMostRecently() {
        assertEquals(2L, autoTarget(listOf(mac(1, true, 10), mac(2, true, 20), mac(3, false, 99)))?.id)
    }

    @Test fun autoHasNoTargetWithoutAnOnlineMac() {
        assertNull(autoTarget(listOf(mac(1, false))))
        assertNull(autoTarget(emptyList()))
        assertNull(autoTarget(null))
    }

    @Test fun aSendInterruptedByARestartCountsAsFailed() {
        val (done, cut) = listOf(rec(1), rec(2, SendState.Sending)).restored()
        assertEquals(SendState.Sent, done.state)
        assertEquals(SendState.Failed, cut.state)
        assertEquals("发送中断，文字已保留", cut.error)
    }

    @Test fun failuresReadAsTheDesignSays() {
        assertEquals("没有在线的电脑，文字已保留", sendFailure(ApiError(409, "没有在线的电脑")))
        assertEquals("这台电脑不在线，文字已保留", sendFailure(ApiError(409, "这台电脑不在线")))
        assertEquals("电脑没有响应", sendFailure(ApiError(504, "电脑没有响应")))
        assertEquals("网络连接失败", sendFailure(IOException()))
    }
}
