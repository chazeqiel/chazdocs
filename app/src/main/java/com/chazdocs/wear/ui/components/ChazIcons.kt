package com.chazdocs.wear.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Lightweight custom vector icons to avoid pulling in material-icons-extended (~15MB).
 */
object ChazIcons {

    val Book: ImageVector by lazy {
        icon("Book") {
            path(fill = SolidColor(Color.White)) {
                moveTo(18f, 2f)
                horizontalLineTo(6f)
                curveTo(4.9f, 2f, 4f, 2.9f, 4f, 4f)
                verticalLineTo(20f)
                curveTo(4f, 21.1f, 4.9f, 22f, 6f, 22f)
                horizontalLineTo(18f)
                curveTo(19.1f, 22f, 20f, 21.1f, 20f, 20f)
                verticalLineTo(4f)
                curveTo(20f, 2.9f, 19.1f, 2f, 18f, 2f)
                close()
                moveTo(9f, 4f)
                horizontalLineTo(11f)
                verticalLineTo(10f)
                lineTo(10f, 9f)
                lineTo(9f, 10f)
                verticalLineTo(4f)
                close()
                moveTo(6f, 20f)
                verticalLineTo(4f)
                horizontalLineTo(7.5f)
                verticalLineTo(20f)
                horizontalLineTo(6f)
                close()
            }
        }
    }

    val Settings: ImageVector by lazy {
        icon("Settings") {
            path(fill = SolidColor(Color.White)) {
                moveTo(19.14f, 12.94f)
                curveTo(19.18f, 12.64f, 19.2f, 12.33f, 19.2f, 12f)
                curveTo(19.2f, 11.67f, 19.18f, 11.36f, 19.13f, 11.06f)
                lineTo(21.16f, 9.48f)
                curveTo(21.34f, 9.34f, 21.39f, 9.07f, 21.28f, 8.86f)
                lineTo(19.36f, 5.54f)
                curveTo(19.24f, 5.33f, 18.99f, 5.25f, 18.77f, 5.33f)
                lineTo(16.38f, 6.29f)
                curveTo(15.88f, 5.91f, 15.34f, 5.59f, 14.75f, 5.35f)
                lineTo(14.39f, 2.81f)
                curveTo(14.36f, 2.57f, 14.16f, 2.4f, 13.91f, 2.4f)
                horizontalLineTo(10.08f)
                curveTo(9.84f, 2.4f, 9.64f, 2.57f, 9.6f, 2.81f)
                lineTo(9.24f, 5.35f)
                curveTo(8.65f, 5.59f, 8.11f, 5.91f, 7.62f, 6.29f)
                lineTo(5.23f, 5.33f)
                curveTo(5.0f, 5.25f, 4.75f, 5.33f, 4.64f, 5.54f)
                lineTo(2.72f, 8.86f)
                curveTo(2.6f, 9.07f, 2.65f, 9.34f, 2.84f, 9.48f)
                lineTo(4.87f, 11.06f)
                curveTo(4.82f, 11.36f, 4.8f, 11.68f, 4.8f, 12f)
                curveTo(4.8f, 12.32f, 4.82f, 12.64f, 4.87f, 12.94f)
                lineTo(2.84f, 14.52f)
                curveTo(2.66f, 14.66f, 2.61f, 14.93f, 2.72f, 15.14f)
                lineTo(4.64f, 18.46f)
                curveTo(4.76f, 18.67f, 5.01f, 18.75f, 5.23f, 18.67f)
                lineTo(7.62f, 17.71f)
                curveTo(8.12f, 18.09f, 8.66f, 18.41f, 9.24f, 18.65f)
                lineTo(9.6f, 21.19f)
                curveTo(9.64f, 21.43f, 9.84f, 21.6f, 10.08f, 21.6f)
                horizontalLineTo(13.91f)
                curveTo(14.16f, 21.6f, 14.36f, 21.43f, 14.39f, 21.19f)
                lineTo(14.75f, 18.65f)
                curveTo(15.34f, 18.41f, 15.88f, 18.09f, 16.38f, 17.71f)
                lineTo(18.77f, 18.67f)
                curveTo(19.0f, 18.75f, 19.25f, 18.67f, 19.36f, 18.46f)
                lineTo(21.28f, 15.14f)
                curveTo(21.4f, 14.93f, 21.35f, 14.66f, 21.16f, 14.52f)
                lineTo(19.14f, 12.94f)
                close()
                moveTo(12f, 15.6f)
                curveTo(10.01f, 15.6f, 8.4f, 13.99f, 8.4f, 12f)
                curveTo(8.4f, 10.01f, 10.01f, 8.4f, 12f, 8.4f)
                curveTo(13.99f, 8.4f, 15.6f, 10.01f, 15.6f, 12f)
                curveTo(15.6f, 13.99f, 13.99f, 15.6f, 12f, 15.6f)
                close()
            }
        }
    }

    val Refresh: ImageVector by lazy {
        icon("Refresh") {
            path(fill = SolidColor(Color.White)) {
                moveTo(17.65f, 6.35f)
                curveTo(16.2f, 4.9f, 14.21f, 4f, 12f, 4f)
                curveTo(7.58f, 4f, 4.01f, 7.58f, 4.01f, 12f)
                curveTo(4.01f, 16.42f, 7.58f, 20f, 12f, 20f)
                curveTo(15.73f, 20f, 18.84f, 17.45f, 19.73f, 14f)
                horizontalLineTo(17.65f)
                curveTo(16.83f, 16.33f, 14.61f, 18f, 12f, 18f)
                curveTo(8.69f, 18f, 6f, 15.31f, 6f, 12f)
                curveTo(6f, 8.69f, 8.69f, 6f, 12f, 6f)
                curveTo(13.66f, 6f, 15.14f, 6.69f, 16.22f, 7.78f)
                lineTo(13f, 11f)
                horizontalLineTo(20f)
                verticalLineTo(4f)
                lineTo(17.65f, 6.35f)
                close()
            }
        }
    }

    val Toc: ImageVector by lazy {
        icon("Toc") {
            path(fill = SolidColor(Color.White)) {
                moveTo(3f, 7f)
                horizontalLineTo(15f)
                verticalLineTo(9f)
                horizontalLineTo(3f)
                close()
                moveTo(3f, 11f)
                horizontalLineTo(15f)
                verticalLineTo(13f)
                horizontalLineTo(3f)
                close()
                moveTo(3f, 15f)
                horizontalLineTo(11f)
                verticalLineTo(17f)
                horizontalLineTo(3f)
                close()
                moveTo(17f, 7f)
                horizontalLineTo(21f)
                verticalLineTo(9f)
                horizontalLineTo(17f)
                close()
                moveTo(17f, 11f)
                horizontalLineTo(21f)
                verticalLineTo(13f)
                horizontalLineTo(17f)
                close()
                moveTo(17f, 15f)
                horizontalLineTo(21f)
                verticalLineTo(17f)
                horizontalLineTo(17f)
                close()
            }
        }
    }

    val Home: ImageVector by lazy {
        icon("Home") {
            path(fill = SolidColor(Color.White)) {
                moveTo(10f, 20f)
                verticalLineTo(14f)
                horizontalLineTo(14f)
                verticalLineTo(20f)
                horizontalLineTo(19f)
                verticalLineTo(12f)
                horizontalLineTo(22f)
                lineTo(12f, 3f)
                lineTo(2f, 12f)
                horizontalLineTo(5f)
                verticalLineTo(20f)
                close()
            }
        }
    }

    val ChevronLeft: ImageVector by lazy {
        icon("ChevronLeft") {
            path(fill = SolidColor(Color.White)) {
                moveTo(15.41f, 7.41f)
                lineTo(14f, 6f)
                lineTo(8f, 12f)
                lineTo(14f, 18f)
                lineTo(15.41f, 16.59f)
                lineTo(10.83f, 12f)
                close()
            }
        }
    }

    val ChevronRight: ImageVector by lazy {
        icon("ChevronRight") {
            path(fill = SolidColor(Color.White)) {
                moveTo(10f, 6f)
                lineTo(8.59f, 7.41f)
                lineTo(13.17f, 12f)
                lineTo(8.59f, 16.59f)
                lineTo(10f, 18f)
                lineTo(16f, 12f)
                close()
            }
        }
    }

    val Close: ImageVector by lazy {
        icon("Close") {
            path(fill = SolidColor(Color.White)) {
                moveTo(19f, 6.41f)
                lineTo(17.59f, 5f)
                lineTo(12f, 10.59f)
                lineTo(6.41f, 5f)
                lineTo(5f, 6.41f)
                lineTo(10.59f, 12f)
                lineTo(5f, 17.59f)
                lineTo(6.41f, 19f)
                lineTo(12f, 13.41f)
                lineTo(17.59f, 19f)
                lineTo(19f, 17.59f)
                lineTo(13.41f, 12f)
                close()
            }
        }
    }

    val Search: ImageVector by lazy {
        icon("Search") {
            path(fill = SolidColor(Color.White)) {
                moveTo(15.5f, 14f)
                horizontalLineTo(14.71f)
                lineTo(14.43f, 13.73f)
                curveTo(15.41f, 12.59f, 16f, 11.11f, 16f, 9.5f)
                curveTo(16f, 5.91f, 13.09f, 3f, 9.5f, 3f)
                curveTo(5.91f, 3f, 3f, 5.91f, 3f, 9.5f)
                curveTo(3f, 13.09f, 5.91f, 16f, 9.5f, 16f)
                curveTo(11.11f, 16f, 12.59f, 15.41f, 13.73f, 14.43f)
                lineTo(14f, 14.71f)
                verticalLineTo(15.5f)
                lineTo(19f, 20.49f)
                lineTo(20.49f, 19f)
                lineTo(15.5f, 14f)
                close()
                moveTo(9.5f, 14f)
                curveTo(7.01f, 14f, 5f, 11.99f, 5f, 9.5f)
                curveTo(5f, 7.01f, 7.01f, 5f, 9.5f, 5f)
                curveTo(11.99f, 5f, 14f, 7.01f, 14f, 9.5f)
                curveTo(14f, 11.99f, 11.99f, 14f, 9.5f, 14f)
                close()
            }
        }
    }

    val Sort: ImageVector by lazy {
        icon("Sort") {
            path(fill = SolidColor(Color.White)) {
                moveTo(3f, 18f)
                horizontalLineTo(9f)
                verticalLineTo(16f)
                horizontalLineTo(3f)
                verticalLineTo(18f)
                close()
                moveTo(3f, 6f)
                verticalLineTo(8f)
                horizontalLineTo(21f)
                verticalLineTo(6f)
                horizontalLineTo(3f)
                close()
                moveTo(3f, 13f)
                horizontalLineTo(15f)
                verticalLineTo(11f)
                horizontalLineTo(3f)
                verticalLineTo(13f)
                close()
            }
        }
    }

    private fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector {
        return ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply(block).build()
    }
}
