package com.example.picospacemystery.data

import com.example.picospacemystery.model.Extent2D
import com.example.picospacemystery.model.RoomLayoutData
import com.example.picospacemystery.model.SurfaceData
import com.example.picospacemystery.model.Vector3Data

/**
 * 房间扫描器：支持 Mock 客厅数据（模拟器/无真机演示）与真实 PICO 平面感知适配器。
 */
class RoomScanner(private val planeProvider: SpatialPlaneProvider? = null) {
    suspend fun scan(useMock: Boolean): RoomLayoutData {
        return if (useMock) {
            mockLivingRoom()
        } else {
            requireNotNull(planeProvider) {
                "请先配置真实 PICO SpatialPlaneProvider 再关闭 Mock 开关。"
            }.enablePassthroughAndScan()
        }
    }

    private fun mockLivingRoom() = RoomLayoutData(
        listOf(
            SurfaceData("floor-1", "floor", Vector3Data(0f, 0f, 0f), Extent2D(5f, 4f)),
            SurfaceData("wall-1", "wall", Vector3Data(0f, 1.4f, -2f), Extent2D(5f, 2.8f)),
            SurfaceData("wall-2", "wall", Vector3Data(-2.5f, 1.4f, 0f), Extent2D(4f, 2.8f)),
            SurfaceData("wall-3", "wall", Vector3Data(2.5f, 1.4f, 0f), Extent2D(4f, 2.8f)),
            SurfaceData("table-1", "table", Vector3Data(0f, 0.75f, -0.5f), Extent2D(1.2f, 0.7f))
        )
    )
}

interface SpatialPlaneProvider {
    suspend fun enablePassthroughAndScan(): RoomLayoutData
}
