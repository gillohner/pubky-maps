package to.pubky.maps.routing

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import to.pubky.maps.ui.AppIcons

/** Icons of directions: Material Icons paths (Apache-2.0), as in AppIcons. */
internal object RoutingIcons {
    val Directions: ImageVector =
        icon(
            "Directions",
            "M21.71 11.29l-9-9c-.39-.39-1.02-.39-1.41 0l-9 9c-.39.39-.39 1.02 0 1.41l9 9c.39.39 1.02.39 1.41 0l9-9" +
                "c.39-.38.39-1.01 0-1.41zM14 14.5V12h-4v3H8v-4c0-.55.45-1 1-1h5V7.5l3.5 3.5-3.5 3.5z",
            autoMirror = true,
        )

    val Car: ImageVector =
        icon(
            "DirectionsCar",
            "M18.92 6.01C18.72 5.42 18.16 5 17.5 5h-11c-.66 0-1.21.42-1.42 1.01L3 12v8c0 .55.45 1 1 1h1c.55 0 1-.45 1-1v-1" +
                "h12v1c0 .55.45 1 1 1h1c.55 0 1-.45 1-1v-8l-2.08-5.99zM6.5 16c-.83 0-1.5-.67-1.5-1.5S5.67 13 6.5 13s1.5.67 1.5 1.5" +
                "S7.33 16 6.5 16zm11 0c-.83 0-1.5-.67-1.5-1.5s.67-1.5 1.5-1.5 1.5.67 1.5 1.5-.67 1.5-1.5 1.5zM5 11l1.5-4.5h11L19 11H5z",
        )

    val Bike: ImageVector =
        icon(
            "DirectionsBike",
            "M15.5 5.5c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zM5 12c-2.8 0-5 2.2-5 5s2.2 5 5 5 5-2.2 5-5-2.2-5-5-5z" +
                "m0 8.5c-1.9 0-3.5-1.6-3.5-3.5s1.6-3.5 3.5-3.5 3.5 1.6 3.5 3.5-1.6 3.5-3.5 3.5zm5.8-10l2.4-2.4.8.8" +
                "c1.3 1.3 3 2.1 5.1 2.1V9c-1.5 0-2.7-.6-3.6-1.5l-1.9-1.9c-.5-.4-1-.6-1.6-.6s-1.1.2-1.4.6L7.8 8.4" +
                "c-.4.4-.6.9-.6 1.4 0 .6.2 1.1.6 1.4L11 14v5h2v-6.2l-2.2-2.3zM19 12c-2.8 0-5 2.2-5 5s2.2 5 5 5 5-2.2 5-5" +
                "-2.2-5-5-5zm0 8.5c-1.9 0-3.5-1.6-3.5-3.5s1.6-3.5 3.5-3.5 3.5 1.6 3.5 3.5-1.6 3.5-3.5 3.5z",
        )

    val Walk: ImageVector =
        icon(
            "DirectionsWalk",
            "M13.5 5.5c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zM9.8 8.9L7 23h2.1l1.8-8 2.1 2v6h2v-7.5l-2.1-2 .6-3" +
                "C14.8 12 16.8 13 19 13v-2c-1.9 0-3.5-1-4.3-2.4l-1-1.6c-.4-.6-1-1-1.7-1-.3 0-.5.1-.8.1L6 8.3V13h2V9.6l1.8-.7",
        )

    val TurnLeft: ImageVector = icon("TurnLeft", "M6.83 11l1.59 1.59L7 14l-4-4 4-4 1.41 1.41L6.83 9H15c1.1 0 2 .9 2 2v9h-2v-9H6.83z")

    val TurnRight: ImageVector = icon("TurnRight", "M17.17 11l-1.59 1.59L17 14l4-4-4-4-1.41 1.41L17.17 9H9c-1.1 0-2 .9-2 2v9h2v-9h8.17z")

    val TurnSlightLeft: ImageVector = icon("TurnSlightLeft", "M11.66 6V4H6v5.66h2V7.41l5 5V20h2v-7.58c0-.53-.21-1.04-.59-1.41l-5-5h2.25z")

    val TurnSlightRight: ImageVector =
        icon(
            "TurnSlightRight",
            "M12.34 6V4H18v5.66h-2V7.41l-5 5V20H9v-7.58c0-.53.21-1.04.59-1.41l5-5h-2.25z",
        )

    val TurnSharpLeft: ImageVector =
        icon(
            "TurnSharpLeft",
            "M6 6.83L4.41 8.41 3 7l4-4 4 4-1.41 1.41L8 6.83V13h8c1.1 0 2 .9 2 2v6h-2v-6H8c-1.1 0-2-.9-2-2V6.83z",
        )

    val TurnSharpRight: ImageVector =
        icon(
            "TurnSharpRight",
            "M18 6.83l1.59 1.59L21 7l-4-4-4 4 1.41 1.41L16 6.83V13H8c-1.1 0-2 .9-2 2v6h2v-6h8c1.1 0 2-.9 2-2V6.83z",
        )

    val UTurnLeft: ImageVector =
        icon(
            "UTurnLeft",
            "M18 9v12h-2V9c0-2.21-1.79-4-4-4S8 6.79 8 9v4.17l1.59-1.59L11 13l-4 4-4-4 1.41-1.41L6 13.17V9c0-3.31 2.69-6 6-6s6 2.69 6 6z",
        )

    val UTurnRight: ImageVector =
        icon(
            "UTurnRight",
            "M6 9v12h2V9c0-2.21 1.79-4 4-4s4 1.79 4 4v4.17l-1.59-1.59L13 13l4 4 4-4-1.41-1.41L18 13.17V9c0-3.31-2.69-6-6-6S6 5.69 6 9z",
        )

    val Straight: ImageVector = icon("Straight", "M11 6.83L9.41 8.41 8 7l4-4 4 4-1.41 1.41L13 6.83V21h-2V6.83z")

    val ForkLeft: ImageVector =
        icon(
            "ForkLeft",
            "M9.41 15.59L8 17l-4-4 4-4 1.41 1.41L7.83 12c1.51-.33 3.73.08 5.17 1.36V6.83l-1.59 1.59L10 7l4-4 4 4-1.41 1.41L15 6.83V21h-2v-4c-.73-2.58-3.07-3.47-5.17-3l1.58 1.59z",
        )

    val ForkRight: ImageVector =
        icon(
            "ForkRight",
            "M14.59 15.59L16 17l4-4-4-4-1.41 1.41L16.17 12c-1.51-.33-3.73.08-5.17 1.36V6.83l1.59 1.59L14 7l-4-4-4 4 1.41 1.41L9 6.83V21h2v-4c.73-2.58 3.07-3.47 5.17-3l-1.58 1.59z",
        )

    val Merge: ImageVector =
        icon(
            "Merge",
            "M6.41 21L5 19.59l4.83-4.83c.75-.75 1.17-1.77 1.17-2.83v-5.1L9.41 8.41 8 7l4-4 4 4-1.41 1.41L13 6.83v5.1c0 1.06.42 2.08 1.17 2.83L19 19.59 17.59 21 12 15.41 6.41 21z",
        )

    /** Material's roundabout_left: round the ring anticlockwise, as where traffic keeps right. */
    val Roundabout: ImageVector =
        icon(
            "Roundabout",
            "M10.08 8c.48-2.84 2.94-5 5.92-5 3.31 0 6 2.69 6 6 0 2.97-2.16 5.44-5 5.92V21h-2v-6.09c0-.98.71-1.8 1.67-1.97" +
                "C18.56 12.63 20 10.98 20 9c0-2.21-1.79-4-4-4-1.98 0-3.63 1.44-3.94 3.33-.17.96-.99 1.67-1.97 1.67H5.83l1.59 1.59L6 13" +
                "L2 9l4-4 1.41 1.41L5.83 8h4.25z",
        )

    val Ferry: ImageVector =
        icon(
            "Ferry",
            "M20 21c-1.39 0-2.78-.47-4-1.32-2.44 1.71-5.56 1.71-8 0C6.78 20.53 5.39 21 4 21H2v2h2c1.38 0 2.74-.35 4-.99 2.52 1.29 5.48 1.29 8 0 1.26.65 2.62.99 4 .99h2v-2h-2zM3.95 19H4c1.6 0 3.02-.88 4-2 .98 1.12 2.4 2 4 2s3.02-.88 4-2c.98 1.12 2.4 2 4 2h.05l1.89-6.68c.08-.26.06-.54-.06-.78s-.34-.42-.6-.5L20 10.62V6c0-1.1-.9-2-2-2h-3V1H9v3H6c-1.1 0-2 .9-2 2v4.62l-1.29.42c-.26.08-.48.26-.6.5s-.15.52-.06.78L3.95 19zM6 6h12v3.97L12 8 6 9.97V6z",
        )

    val TripOrigin: ImageVector =
        icon(
            "TripOrigin",
            "M2 12C2 6.48 6.48 2 12 2s10 4.48 10 10-4.48 10-10 10S2 17.52 2 12zm10 6c3.31 0 6-2.69 6-6s-2.69-6-6-6-6 2.69-6 6 2.69 6 6 6z",
        )

    /** Material's navigation arrow: Start (guidance) and Re-centre on the user while it guides them. */
    val Navigation: ImageVector = icon("Navigation", "M12 2L4.5 20.29l.71.71L12 18l6.79 3 .71-.71z")

    /** Guidance's voice is on: the button that switches it off. */
    val VolumeUp: ImageVector =
        icon(
            "VolumeUp",
            "M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02zM14 3.23v2.06" +
                "c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7-8.77s-2.99-7.86-7-8.77z",
            autoMirror = true,
        )

    /** Guidance's voice is off: the button that switches it on. */
    val VolumeOff: ImageVector =
        icon(
            "VolumeOff",
            "M16.5 12c0-1.77-1.02-3.29-2.5-4.03v2.21l2.45 2.45c.03-.2.05-.41.05-.63zm2.5 0c0 .94-.2 1.82-.54 2.64" +
                "l1.51 1.51C20.63 14.91 21 13.5 21 12c0-4.28-2.99-7.86-7-8.77v2.06c2.89.86 5 3.54 5 6.71zM4.27 3L3 4.27" +
                " 7.73 9H3v6h4l5 5v-6.73l4.25 4.25c-.67.52-1.42.93-2.25 1.18v2.06c1.38-.31 2.63-.95 3.69-1.81L19.73 21" +
                " 21 19.73l-9-9L4.27 3zM12 4L9.91 6.09 12 8.18V4z",
            autoMirror = true,
        )

    val ExpandMore: ImageVector = icon("ExpandMore", "M16.59 8.59L12 13.17 7.41 8.59 6 10l6 6 6-6z")

    val ExpandLess: ImageVector = icon("ExpandLess", "M12 8l-6 6 1.41 1.41L12 10.83l4.59 4.58L18 14z")

    /** Swaps a route's start and destination, as Google Maps' directions do. */
    val SwapVert: ImageVector = icon("SwapVert", "M16 17.01V10h-2v7.01h-3L15 21l4-3.99h-3zM9 3L5 6.99h3V14h2V6.99h3L9 3z")

    /** Mirrored in right-to-left languages, where the previous step is on the right. */
    val ChevronLeft: ImageVector = icon("ChevronLeft", "M15.41 7.41L14 6l-6 6 6 6 1.41-1.41L10.83 12z", autoMirror = true)

    val ChevronRight: ImageVector = icon("ChevronRight", "M10 6L8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z", autoMirror = true)

    private fun icon(
        name: String,
        pathData: String,
        autoMirror: Boolean = false,
    ): ImageVector =
        ImageVector
            .Builder(
                name = name,
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
                autoMirror = autoMirror,
            ).addPath(
                pathData = PathParser().parsePathString(pathData).toNodes(),
                fill = SolidColor(Color.Black),
            ).build()
}

/** The mode's icon on its chip. */
internal val TravelMode.icon: ImageVector
    get() =
        when (this) {
            TravelMode.Car -> RoutingIcons.Car
            TravelMode.Bike -> RoutingIcons.Bike
            TravelMode.Walk -> RoutingIcons.Walk
        }

/** The icon of a route step. */
internal val Turn.icon: ImageVector
    get() =
        when (this) {
            Turn.Depart -> RoutingIcons.TripOrigin

            Turn.Straight -> RoutingIcons.Straight

            Turn.SlightLeft -> RoutingIcons.TurnSlightLeft

            Turn.Left -> RoutingIcons.TurnLeft

            Turn.SharpLeft -> RoutingIcons.TurnSharpLeft

            Turn.UTurnLeft -> RoutingIcons.UTurnLeft

            Turn.SlightRight -> RoutingIcons.TurnSlightRight

            Turn.Right -> RoutingIcons.TurnRight

            Turn.SharpRight -> RoutingIcons.TurnSharpRight

            Turn.UTurnRight -> RoutingIcons.UTurnRight

            Turn.KeepLeft -> RoutingIcons.ForkLeft

            Turn.KeepRight -> RoutingIcons.ForkRight

            Turn.Merge -> RoutingIcons.Merge

            // Anticlockwise, as in the countries the app is made for first (traffic on the right).
            Turn.Roundabout -> RoutingIcons.Roundabout

            Turn.Ferry -> RoutingIcons.Ferry

            Turn.Arrive -> AppIcons.Place
        }

/**
 * The icon of a step of these directions' route: its [turn]'s, but the arrival of a route swapped back
 * to the user's position is their location (the icon of the route card's "To your location"), not the
 * place's pin, as the place is where that route starts. A route back to a chosen start arrives at a
 * place's pin. Every route's start keeps the ring ([RoutingIcons.TripOrigin]), as Google Maps' trip
 * origin: the start's icon says where the route begins, the end's what the user arrives at.
 */
internal fun Directions.stepIcon(turn: Turn): ImageVector {
    val endsAtUser = reversed && from == null
    return if (turn == Turn.Arrive && endsAtUser) AppIcons.MyLocation else turn.icon
}
