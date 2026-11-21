package dev.rustdroid.ide.ui.components

import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Icons missing from material-icons-core, built with the materialIcon DSL
 * using standard Material Design path data (Apache-2.0).
 */
object RdIcons {

    /** Builds an icon from standard Material path data (Apache-2.0). */
    private fun fromPathData(name: String, pathData: String): ImageVector =
        materialIcon(name = name) {
            addPath(
                pathData = androidx.compose.ui.graphics.vector.addPathNodes(pathData),
                fill = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Black),
            )
        }

    /** R4A: Material "extension" (puzzle piece) — dependencies / crates. */
    val Package: ImageVector by lazy {
        fromPathData(
            "RdPackage",
            "M20.5,11H19V7c0,-1.1 -0.9,-2 -2,-2h-4V3.5C13,2.12 11.88,1 10.5,1S8,2.12 8,3.5V5H4" +
                "c-1.1,0 -1.99,0.9 -1.99,2v3.8H3.5c1.49,0 2.7,1.21 2.7,2.7s-1.21,2.7 -2.7,2.7H2V20" +
                "c0,1.1 0.9,2 2,2h3.8v-1.5c0,-1.49 1.21,-2.7 2.7,-2.7c1.49,0 2.7,1.21 2.7,2.7V22H17" +
                "c1.1,0 2,-0.9 2,-2v-4h1.5c1.38,0 2.5,-1.12 2.5,-2.5S21.88,11 20.5,11z",
        )
    }

    /** R4A: Material "insert_drive_file" — generic file. */
    val FileGeneric: ImageVector by lazy {
        fromPathData(
            "RdFileGeneric",
            "M6,2c-1.1,0 -1.99,0.9 -1.99,2L4,20c0,1.1 0.89,2 1.99,2H18c1.1,0 2,-0.9 2,-2V8l-6,-6H6z" +
                "M13,9V3.5L18.5,9H13z",
        )
    }

    /** R4A: Material "terminal" (window with a >_ prompt). */
    val Console: ImageVector by lazy {
        fromPathData(
            "RdConsole",
            "M20,4H4C2.89,4 2,4.9 2,6v12c0,1.1 0.89,2 2,2h16c1.1,0 2,-0.9 2,-2V6C22,4.9 21.11,4 20,4z" +
                "M20,18H4V8h16V18zM18,17h-6v-2h6V17zM7.5,17l-1.41,-1.41L8.67,13l-2.59,-2.59L7.5,9l4,4L7.5,17z",
        )
    }

    val Folder: ImageVector by lazy {
        materialIcon(name = "RdFolder") {
            materialPath {
                moveTo(10.0f, 4.0f)
                horizontalLineTo(4.0f)
                curveTo(2.9f, 4.0f, 2.0f, 4.9f, 2.0f, 6.0f)
                lineTo(2.0f, 18.0f)
                curveTo(2.0f, 19.1f, 2.9f, 20.0f, 4.0f, 20.0f)
                horizontalLineTo(20.0f)
                curveTo(21.1f, 20.0f, 22.0f, 19.1f, 22.0f, 18.0f)
                lineTo(22.0f, 8.0f)
                curveTo(22.0f, 6.9f, 21.1f, 6.0f, 20.0f, 6.0f)
                lineTo(12.0f, 6.0f)
                lineTo(10.0f, 4.0f)
                close()
            }
        }
    }

    /** Material "folder_open" — open folder flap (for open-folder-as-project). */
    val FolderOpen: ImageVector by lazy {
        materialIcon(name = "RdFolderOpen") {
            materialPath {
                // exact Material folder_open path data (Apache-2.0)
                moveTo(20.0f, 6.0f)
                horizontalLineToRelative(-8.0f)
                lineToRelative(-2.0f, -2.0f)
                horizontalLineTo(4.0f)
                curveTo(2.9f, 4.0f, 2.01f, 4.9f, 2.01f, 6.0f)
                lineTo(2.0f, 18.0f)
                curveTo(2.0f, 19.1f, 2.9f, 20.0f, 4.0f, 20.0f)
                horizontalLineToRelative(16.0f)
                curveTo(21.1f, 20.0f, 22.0f, 19.1f, 22.0f, 18.0f)
                verticalLineTo(8.0f)
                curveTo(22.0f, 6.9f, 21.1f, 6.0f, 20.0f, 6.0f)
                close()
                moveTo(20.0f, 18.0f)
                lineTo(4.0f, 18.0f)
                lineTo(4.0f, 8.0f)
                horizontalLineToRelative(5.17f)
                lineToRelative(2.0f, 2.0f)
                horizontalLineTo(20.0f)
                verticalLineToRelative(8.0f)
                close()
            }
        }
    }

    val Stop: ImageVector by lazy {
        materialIcon(name = "RdStop") {
            materialPath {
                moveTo(6.0f, 6.0f)
                horizontalLineTo(18.0f)
                verticalLineTo(18.0f)
                horizontalLineTo(6.0f)
                close()
            }
        }
    }

    val Send: ImageVector by lazy {
        materialIcon(name = "RdSend") {
            materialPath {
                // Material "send" (paper plane)
                moveTo(2.01f, 21.0f)
                lineTo(23.0f, 12.0f)
                lineTo(2.01f, 3.0f)
                lineTo(2.0f, 10.0f)
                lineTo(17.0f, 12.0f)
                lineTo(2.0f, 14.0f)
                close()
            }
        }
    }

    val Terminal: ImageVector by lazy {
        materialIcon(name = "RdTerminal") {
            materialPath {
                // code chevrons: < / >
                moveTo(9.4f, 16.6f)
                lineTo(4.8f, 12.0f)
                lineTo(9.4f, 7.4f)
                lineTo(8.0f, 6.0f)
                lineTo(2.0f, 12.0f)
                lineTo(8.0f, 18.0f)
                lineTo(9.4f, 16.6f)
                close()
                moveTo(14.6f, 16.6f)
                lineTo(19.2f, 12.0f)
                lineTo(14.6f, 7.4f)
                lineTo(16.0f, 6.0f)
                lineTo(22.0f, 12.0f)
                lineTo(16.0f, 18.0f)
                lineTo(14.6f, 16.6f)
                close()
            }
        }
    }

    /** Material "content_copy" — two stacked rectangles (outlined). */
    val Copy: ImageVector by lazy {
        materialIcon(name = "RdCopy") {
            materialPath {
                // exact Material content_copy path data (Apache-2.0)
                moveTo(16.0f, 1.0f)
                horizontalLineTo(4.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(14.0f)
                horizontalLineToRelative(2.0f)
                verticalLineTo(3.0f)
                horizontalLineToRelative(12.0f)
                verticalLineTo(1.0f)
                close()
                moveToRelative(3.0f, 4.0f)
                horizontalLineTo(8.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(14.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(11.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                verticalLineTo(7.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                close()
                moveToRelative(0.0f, 16.0f)
                horizontalLineTo(8.0f)
                verticalLineTo(7.0f)
                horizontalLineToRelative(11.0f)
                verticalLineToRelative(14.0f)
                close()
            }
        }
    }
}
