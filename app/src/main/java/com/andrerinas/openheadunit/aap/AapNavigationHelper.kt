package com.andrerinas.openheadunit.aap

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.aap.protocol.proto.NavigationStatus
import com.andrerinas.openheadunit.aap.protocol.proto.NavigationStatus.NextTurnDetail.NextEvent as LegacyNextEvent
import com.andrerinas.openheadunit.aap.protocol.proto.NavigationStatus.NextTurnDetail.Side as LegacySide
import com.andrerinas.openheadunit.contract.NavigationUpdateIntent
import com.andrerinas.openheadunit.utils.AppLog

class AapNavigationHelper(
    private val context: Context
) {
    data class TimedMessage<T>(
        val payload: T,
        val updatedAtElapsedRealtimeMs: Long
    )

    data class NavigationSnapshot(
        var clusterStatus: TimedMessage<NavigationStatus.NavigationClusterStatus>? = null,
        @Deprecated(
            message = "This message may be not send by device",
            level = DeprecationLevel.WARNING
        )
        var nextTurnDetail: TimedMessage<NavigationStatus.NextTurnDetail>? = null,
        @Deprecated(
            message = "This message may be not send by device",
            level = DeprecationLevel.WARNING
        )
        var nextTurnDistance: TimedMessage<NavigationStatus.NextTurnDistanceEvent>? = null,
        var navigationState: TimedMessage<NavigationStatus.NavigationState>? = null,
        var currentPosition: TimedMessage<NavigationStatus.NavigationCurrentPosition>? = null,
        var currentStreet: TimedMessage<String>? = null
    )

    private data class FullNavigationMessage(
        val distanceMeters: Int?,
        val timeSeconds: Int?,
        val road: String,
        @Deprecated(
            message = "Legacy NextTurnDetail.NextEvent wire value; remove with NavigationUpdateIntent.nextEventType and maneuverTypeToLegacyNextEvent.",
            level = DeprecationLevel.WARNING
        )
        val nextEventType: Int,
        val actionText: String,
        @Deprecated(
            message = "Legacy NextTurnDetail.Side (1/2/3); remove with NavigationUpdateIntent.turnSide and maneuverTypeToLegacyTurnSide.",
            level = DeprecationLevel.WARNING
        )
        val turnSide: Int?,
        val turnNumber: Int?,
        val turnAngle: Int?,
        val nextManeuver: Int?,
        val totalDistanceMeters: Int?,
        val totalTimeSeconds: Long?,
        val estimatedArrival: String?
    )

    fun nowElapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()

    @Suppress("DEPRECATION") // Single bridge: legacy nextEventType/turnSide → NavigationUpdateIntent
    fun sendFullNavigationBroadcast(snapshot: NavigationSnapshot, navEventType: Int) {
        val prepared = prepareFullNavigationMessage(snapshot, navEventType)
        val intent = NavigationUpdateIntent(
            distanceMeters = prepared.distanceMeters,
            timeSeconds = prepared.timeSeconds,
            road = prepared.road,
            nextEventType = prepared.nextEventType,
            actionText = prepared.actionText,
            turnSide = prepared.turnSide,
            turnNumber = prepared.turnNumber,
            turnAngle = prepared.turnAngle,
            totalDistanceMeters = prepared.totalDistanceMeters,
            totalTimeSeconds = prepared.totalTimeSeconds,
            estimatedArrival = prepared.estimatedArrival
        )
        context.applicationContext.sendBroadcast(intent, NavigationUpdateIntent.BROADCAST_PERMISSION)
    }

    fun showNotificationForSnapshot(snapshot: NavigationSnapshot, distanceMeters: Int?) {
        val detail = snapshot.nextTurnDetail?.payload
        val state = snapshot.navigationState?.payload
        val currentPosition = snapshot.currentPosition?.payload
        val actionFromDetail = detail
            ?.takeIf { it.hasNextTurn() }
            ?.let { nextEventToAction(it.nextTurn) }
        val actionFromState = state?.stepsList?.firstOrNull()
            ?.takeIf { it.hasManeuver() }
            ?.maneuver
            ?.type
            ?.let { maneuverTypeToAction(it) }
        val action = actionFromDetail ?: actionFromState ?: context.getString(R.string.nav_action_unknown)

        val roadFromPosition = currentPosition
            ?.takeIf { it.hasCurrentRoad() && it.currentRoad.hasName() }
            ?.currentRoad
            ?.name
            ?.takeIf { it.isNotBlank() }
        val roadFromState = state?.stepsList?.firstOrNull()
            ?.takeIf { it.hasRoad() && it.road.hasName() }
            ?.road
            ?.name
            ?.takeIf { it.isNotBlank() }
        val street = (
            roadFromPosition
                ?: snapshot.currentStreet?.payload?.takeIf { it.isNotBlank() }
                ?: detail?.road?.takeIf { it.isNotBlank() }
                ?: roadFromState
                ?: ""
            ).ifBlank { "—" }
        showNotification(distanceMeters = distanceMeters, action = action, street = street)
    }

    fun cancelNotification() {
        val appContext = context.applicationContext
        (appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(NAV_NOTIFICATION_ID)
    }

    @Suppress("DEPRECATION") // Localized use of deprecated legacy mapping until broadcast API drops NextTurnDetail fields
    private fun prepareFullNavigationMessage(snapshot: NavigationSnapshot, navEventType: Int): FullNavigationMessage {
        val detail = snapshot.nextTurnDetail?.payload
        val turnDistance = snapshot.nextTurnDistance?.payload
        val state = snapshot.navigationState?.payload
        val currentPosition = snapshot.currentPosition?.payload

        val stepDistance = currentPosition?.takeIf { it.hasStepDistance() }?.stepDistance
        val distanceMeters = stepDistance
            ?.takeIf { it.hasDistance() && it.distance.hasMeters() }
            ?.distance
            ?.meters
            ?: turnDistance?.distanceMeters?.takeIf { it >= 0 }
        val timeSeconds = stepDistance
            ?.takeIf { it.hasTimeToStepSeconds() }
            ?.timeToStepSeconds
            ?.coerceAtLeast(0L)
            ?.coerceAtMost(Int.MAX_VALUE.toLong())
            ?.toInt()
            ?: turnDistance?.timeToTurnSeconds?.takeIf { it >= 0 }

        val roadFromPosition = currentPosition
            ?.takeIf { it.hasCurrentRoad() && it.currentRoad.hasName() }
            ?.currentRoad
            ?.name
            ?.takeIf { it.isNotBlank() }
        val roadFromState = state?.stepsList?.firstOrNull()
            ?.takeIf { it.hasRoad() && it.road.hasName() }
            ?.road
            ?.name
            ?.takeIf { it.isNotBlank() }
        val road = (roadFromPosition
            ?: snapshot.currentStreet?.payload?.takeIf { it.isNotBlank() }
            ?: roadFromState
            ?: detail?.road?.takeIf { it.isNotBlank() }
            ?: "").ifBlank { "—" }

        val maneuverType = state?.stepsList?.firstOrNull()
            ?.takeIf { it.hasManeuver() }
            ?.maneuver
            ?.takeIf { it.hasType() }
            ?.type

        val nextEventType = detail?.takeIf { it.hasNextTurn() }?.nextTurn?.number
            ?: (maneuverType?.let { maneuverTypeToLegacyNextEvent(it) }
                ?: LegacyNextEvent.UNKNOWN.number)
        val turnSide = detail
            ?.takeIf { it.hasSide() }
            ?.side
            ?.number
            ?: maneuverType?.let { maneuverTypeToLegacyTurnSide(it) }
        val nextManeuver = state?.stepsList?.firstOrNull()?.maneuver?.type?.number
        val turnNumber = state?.stepsList?.firstOrNull()?.maneuver?.roundaboutExitNumber
            ?: detail?.takeIf { it.hasTurnNumber() }?.turnNumber
        val turnAngle = state?.stepsList?.firstOrNull()?.maneuver?.roundaboutExitAngle
            ?: detail?.takeIf { it.hasTurnAngle() }?.turnAngle
val actionText = state?.stepsList?.firstOrNull()?.maneuver?.type?.let { maneuverTypeToAction(it) }
    ?: detail?.takeIf { it.hasNextTurn() }?.let { nextEventToAction(it.nextTurn) }
    ?: context.getString(R.string.nav_action_unknown)

        val destFirst = currentPosition?.destinationDistancesList?.firstOrNull()
        val totalDistanceMeters = destFirst
            ?.takeIf { it.hasDistance() && it.distance.hasMeters() }
            ?.distance
            ?.meters
            ?.takeIf { it >= 0 }
        val totalTimeSeconds = destFirst
            ?.takeIf { it.hasTimeToArrivalSeconds() }
            ?.timeToArrivalSeconds
            ?.takeIf { it >= 0L }
        val estimatedArrival = destFirst
            ?.takeIf { it.hasEstimatedTimeAtArrival() }
            ?.estimatedTimeAtArrival
            ?.takeIf { it.isNotBlank() }

        AppLog.d(
            "Nav: emit debounced eventType=$navEventType " +
                "statusAt=${snapshot.clusterStatus?.updatedAtElapsedRealtimeMs} " +
                "turnAt=${snapshot.nextTurnDetail?.updatedAtElapsedRealtimeMs} " +
                "distanceAt=${snapshot.nextTurnDistance?.updatedAtElapsedRealtimeMs} " +
                "stateAt=${snapshot.navigationState?.updatedAtElapsedRealtimeMs} " +
                "positionAt=${snapshot.currentPosition?.updatedAtElapsedRealtimeMs}"
        )

        return FullNavigationMessage(
            distanceMeters = distanceMeters,
            timeSeconds = timeSeconds,
            road = road,
            nextEventType = nextEventType,
            actionText = actionText,
            turnSide = turnSide,
            turnNumber = turnNumber,
            turnAngle = turnAngle,
            nextManeuver = nextManeuver,
            totalDistanceMeters = totalDistanceMeters,
            totalTimeSeconds = totalTimeSeconds,
            estimatedArrival = estimatedArrival
        )
    }

    private fun showNotification(distanceMeters: Int?, action: String, street: String) {
        val appContext = context.applicationContext
        val title = if (distanceMeters != null && distanceMeters >= 0) {
            context.getString(R.string.nav_notification_title_format, distanceMeters, action)
        } else {
            action
        }
        val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val notification = NotificationCompat.Builder(appContext, NAV_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_aa)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.nav_notification_street_format, street))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    appContext,
                    0,
                    AapProjectionActivity.intent(appContext),
                    pendingIntentFlags
                )
            )
            .build()
        (appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NAV_NOTIFICATION_ID, notification)
    }

    private fun nextEventToAction(nextEvent: NavigationStatus.NextTurnDetail.NextEvent): String {
        return when (nextEvent) {
            NavigationStatus.NextTurnDetail.NextEvent.UNKNOWN -> context.getString(R.string.nav_action_unknown)
            NavigationStatus.NextTurnDetail.NextEvent.DEPART -> context.getString(R.string.nav_action_depart)
            NavigationStatus.NextTurnDetail.NextEvent.NAME_CHANGE -> context.getString(R.string.nav_action_name_change)
            NavigationStatus.NextTurnDetail.NextEvent.SLIGHT_TURN -> context.getString(R.string.nav_action_slight_turn)
            NavigationStatus.NextTurnDetail.NextEvent.TURN -> context.getString(R.string.nav_action_turn)
            NavigationStatus.NextTurnDetail.NextEvent.SHARP_TURN -> context.getString(R.string.nav_action_sharp_turn)
            NavigationStatus.NextTurnDetail.NextEvent.U_TURN -> context.getString(R.string.nav_action_uturn)
            NavigationStatus.NextTurnDetail.NextEvent.ON_RAMP -> context.getString(R.string.nav_action_on_ramp)
            NavigationStatus.NextTurnDetail.NextEvent.OFFRAMP -> context.getString(R.string.nav_action_off_ramp)
            NavigationStatus.NextTurnDetail.NextEvent.FORK -> context.getString(R.string.nav_action_merge)
            NavigationStatus.NextTurnDetail.NextEvent.MERGE -> context.getString(R.string.nav_action_merge)
            NavigationStatus.NextTurnDetail.NextEvent.ROUNDABOUT_ENTER -> context.getString(R.string.nav_action_roundabout_enter)
            NavigationStatus.NextTurnDetail.NextEvent.ROUNDABOUT_EXIT -> context.getString(R.string.nav_action_roundabout_exit)
            NavigationStatus.NextTurnDetail.NextEvent.ROUNDABOUT_ENTER_AND_EXIT -> context.getString(R.string.nav_action_roundabout)
            NavigationStatus.NextTurnDetail.NextEvent.STRAIGHT -> context.getString(R.string.nav_action_straight)
            NavigationStatus.NextTurnDetail.NextEvent.FERRY_BOAT -> context.getString(R.string.nav_action_ferry)
            NavigationStatus.NextTurnDetail.NextEvent.FERRY_TRAIN -> context.getString(R.string.nav_action_ferry_train)
            NavigationStatus.NextTurnDetail.NextEvent.DESTINATION -> context.getString(R.string.nav_action_destination)
        }
    }

    /**
     * Maps instrument-cluster [NavigationManeuver.NavigationType] to legacy broadcast
     * [NextTurnDetail.NextEvent] wire values expected by external consumers.
     */
    @Deprecated(
        message = "Remove together with FullNavigationMessage.nextEventType and NavigationUpdateIntent.nextEventType.",
        level = DeprecationLevel.WARNING
    )
    private fun maneuverTypeToLegacyNextEvent(
        type: NavigationStatus.NavigationManeuver.NavigationType
    ): Int {
        return when (type) {
            NavigationStatus.NavigationManeuver.NavigationType.UNKNOWN -> LegacyNextEvent.UNKNOWN.number
            NavigationStatus.NavigationManeuver.NavigationType.DEPART -> LegacyNextEvent.DEPART.number
            NavigationStatus.NavigationManeuver.NavigationType.NAME_CHANGE -> LegacyNextEvent.NAME_CHANGE.number
            NavigationStatus.NavigationManeuver.NavigationType.KEEP_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.KEEP_RIGHT -> LegacyNextEvent.STRAIGHT.number
            NavigationStatus.NavigationManeuver.NavigationType.TURN_SLIGHT_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.TURN_SLIGHT_RIGHT -> LegacyNextEvent.SLIGHT_TURN.number
            NavigationStatus.NavigationManeuver.NavigationType.TURN_NORMAL_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.TURN_NORMAL_RIGHT -> LegacyNextEvent.TURN.number
            NavigationStatus.NavigationManeuver.NavigationType.TURN_SHARP_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.TURN_SHARP_RIGHT -> LegacyNextEvent.SHARP_TURN.number
            NavigationStatus.NavigationManeuver.NavigationType.U_TURN_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.U_TURN_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_U_TURN_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_U_TURN_RIGHT -> LegacyNextEvent.U_TURN.number
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_SLIGHT_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_SLIGHT_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_NORMAL_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_NORMAL_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_SHARP_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_SHARP_RIGHT -> LegacyNextEvent.ON_RAMP.number
            NavigationStatus.NavigationManeuver.NavigationType.OFF_RAMP_SLIGHT_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.OFF_RAMP_SLIGHT_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.OFF_RAMP_NORMAL_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.OFF_RAMP_NORMAL_RIGHT -> LegacyNextEvent.OFFRAMP.number
            NavigationStatus.NavigationManeuver.NavigationType.FORK_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.FORK_RIGHT -> LegacyNextEvent.FORK.number
            NavigationStatus.NavigationManeuver.NavigationType.MERGE_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.MERGE_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.MERGE_SIDE_UNSPECIFIED -> LegacyNextEvent.MERGE.number
            NavigationStatus.NavigationManeuver.NavigationType.ROUNDABOUT_ENTER -> LegacyNextEvent.ROUNDABOUT_ENTER.number
            NavigationStatus.NavigationManeuver.NavigationType.ROUNDABOUT_EXIT -> LegacyNextEvent.ROUNDABOUT_EXIT.number
            NavigationStatus.NavigationManeuver.NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CW,
            NavigationStatus.NavigationManeuver.NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CW_WITH_ANGLE,
            NavigationStatus.NavigationManeuver.NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CCW,
            NavigationStatus.NavigationManeuver.NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE ->
                LegacyNextEvent.ROUNDABOUT_ENTER_AND_EXIT.number
            NavigationStatus.NavigationManeuver.NavigationType.STRAIGHT -> LegacyNextEvent.STRAIGHT.number
            NavigationStatus.NavigationManeuver.NavigationType.FERRY_BOAT -> LegacyNextEvent.FERRY_BOAT.number
            NavigationStatus.NavigationManeuver.NavigationType.FERRY_TRAIN -> LegacyNextEvent.FERRY_TRAIN.number
            NavigationStatus.NavigationManeuver.NavigationType.DESTINATION,
            NavigationStatus.NavigationManeuver.NavigationType.DESTINATION_STRAIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.DESTINATION_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.DESTINATION_RIGHT -> LegacyNextEvent.DESTINATION.number
        }
    }

    /**
     * Maps [NavigationManeuver.NavigationType] to legacy [NextTurnDetail.Side] (1=LEFT, 2=RIGHT).
     * Returns null when side is not meaningful → [NavigationUpdateIntent] uses UNSPECIFIED (3).
     */
    @Deprecated(
        message = "Remove together with FullNavigationMessage.turnSide and NavigationUpdateIntent.turnSide.",
        level = DeprecationLevel.WARNING
    )
    private fun maneuverTypeToLegacyTurnSide(
        type: NavigationStatus.NavigationManeuver.NavigationType
    ): Int? {
        return when (type) {
            NavigationStatus.NavigationManeuver.NavigationType.KEEP_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.TURN_SLIGHT_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.TURN_NORMAL_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.TURN_SHARP_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.U_TURN_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_SLIGHT_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_NORMAL_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_SHARP_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_U_TURN_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.OFF_RAMP_SLIGHT_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.OFF_RAMP_NORMAL_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.FORK_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.MERGE_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.DESTINATION_LEFT -> LegacySide.LEFT.number

            NavigationStatus.NavigationManeuver.NavigationType.KEEP_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.TURN_SLIGHT_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.TURN_NORMAL_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.TURN_SHARP_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.U_TURN_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_SLIGHT_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_NORMAL_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_SHARP_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_U_TURN_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.OFF_RAMP_SLIGHT_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.OFF_RAMP_NORMAL_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.FORK_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.MERGE_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.DESTINATION_RIGHT -> LegacySide.RIGHT.number

            else -> null
        }
    }

    private fun maneuverTypeToAction(type: NavigationStatus.NavigationManeuver.NavigationType): String {
        return when (type) {
            NavigationStatus.NavigationManeuver.NavigationType.DEPART -> context.getString(R.string.nav_action_depart)
            NavigationStatus.NavigationManeuver.NavigationType.NAME_CHANGE -> context.getString(R.string.nav_action_name_change)
            NavigationStatus.NavigationManeuver.NavigationType.KEEP_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.KEEP_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.TURN_SLIGHT_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.TURN_SLIGHT_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.TURN_NORMAL_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.TURN_NORMAL_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.TURN_SHARP_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.TURN_SHARP_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.U_TURN_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.U_TURN_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_SLIGHT_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_SLIGHT_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_NORMAL_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_NORMAL_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_SHARP_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_SHARP_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_U_TURN_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_U_TURN_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.OFF_RAMP_SLIGHT_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.OFF_RAMP_SLIGHT_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.OFF_RAMP_NORMAL_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.OFF_RAMP_NORMAL_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.FORK_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.FORK_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.MERGE_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.MERGE_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.MERGE_SIDE_UNSPECIFIED -> context.getString(R.string.nav_action_turn)
            NavigationStatus.NavigationManeuver.NavigationType.ROUNDABOUT_ENTER,
            NavigationStatus.NavigationManeuver.NavigationType.ROUNDABOUT_EXIT,
            NavigationStatus.NavigationManeuver.NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CW,
            NavigationStatus.NavigationManeuver.NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CW_WITH_ANGLE,
            NavigationStatus.NavigationManeuver.NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CCW,
            NavigationStatus.NavigationManeuver.NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE -> context.getString(R.string.nav_action_roundabout)
            NavigationStatus.NavigationManeuver.NavigationType.STRAIGHT -> context.getString(R.string.nav_action_straight)
            NavigationStatus.NavigationManeuver.NavigationType.FERRY_BOAT -> context.getString(R.string.nav_action_ferry)
            NavigationStatus.NavigationManeuver.NavigationType.FERRY_TRAIN -> context.getString(R.string.nav_action_ferry_train)
            NavigationStatus.NavigationManeuver.NavigationType.DESTINATION,
            NavigationStatus.NavigationManeuver.NavigationType.DESTINATION_STRAIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.DESTINATION_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.DESTINATION_RIGHT -> context.getString(R.string.nav_action_destination)
            NavigationStatus.NavigationManeuver.NavigationType.UNKNOWN -> context.getString(R.string.nav_action_unknown)
        }
    }

    companion object {
        const val NAV_CHANNEL_ID = "headunit_navigation"
        private const val NAV_NOTIFICATION_ID = 2

        fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    NAV_CHANNEL_ID,
                    context.getString(R.string.nav_notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(R.string.nav_notification_channel_description)
                    setShowBadge(false)
                }
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .createNotificationChannel(channel)
            }
        }
    }
}
