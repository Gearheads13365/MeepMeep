package com.noahbres.meepmeep.roadrunner.entity

import com.acmerobotics.roadrunner.Action
import com.acmerobotics.roadrunner.Pose2d
import com.acmerobotics.roadrunner.SleepAction
import com.noahbres.meepmeep.MeepMeep
import com.noahbres.meepmeep.core.colorscheme.ColorScheme
import com.noahbres.meepmeep.core.entity.BotEntity
import com.noahbres.meepmeep.core.entity.EntityEventListener
import com.noahbres.meepmeep.core.exhaustive
import com.noahbres.meepmeep.roadrunner.Constraints
import com.noahbres.meepmeep.roadrunner.DriveShim
import com.noahbres.meepmeep.roadrunner.DriveTrainType
import com.noahbres.meepmeep.roadrunner.ui.TrajectoryProgressSliderMaster
import kotlin.math.min

// TODO(ryanbrott): seems like the bot should own the path entities and selectively update/render the ones
// that need it and also update the pose (perhaps there should be another Entity interface?)
class RoadRunnerBotEntity(
    meepMeep: MeepMeep,
    private var constraints: Constraints,

    width: Double, height: Double,
    pose: Pose2d,

    val colorScheme: ColorScheme,
    opacity: Double,

    private var driveTrainType: DriveTrainType = DriveTrainType.MECANUM,

    var listenToSwitchThemeRequest: Boolean = false
) : BotEntity(meepMeep, width, height, pose, colorScheme, opacity), EntityEventListener {
    override val tag = "RR_BOT_ENTITY"

    override var zIndex: Int = 0

    var drive = DriveShim(driveTrainType, constraints, pose)

    var currentAction: Action? = null

    private var actionEntity: ActionEntity? = null

    var looping = true
    private var running = false

    private var trajectorySequenceElapsedTime = 0.0
        set(value) {
            actionEntity?.trajectoryProgress = value
            field = value
        }

    var trajectoryPaused = false

    private var sliderMaster: TrajectoryProgressSliderMaster? = null
    private var sliderMasterIndex: Int? = null

    override fun update(deltaTime: Long) {
        if (!running) return

        if (!trajectoryPaused) trajectorySequenceElapsedTime += deltaTime / 1e9

        val (dt, timeline) = actionTimeline(currentAction!!)

        when {
            trajectorySequenceElapsedTime <= dt -> {
                var segment: Action? = null
                var segmentOffsetTime = 0.0

                for ((beginTime, seg) in timeline) {
                    if (beginTime > trajectorySequenceElapsedTime) break

                    segment = seg
                    segmentOffsetTime = trajectorySequenceElapsedTime - beginTime
                }

                pose = when (segment) {
                    is TurnAction -> segment.t[segmentOffsetTime].value()
                    is TrajectoryAction -> segment.t[segmentOffsetTime].value()
                    else -> throw RuntimeException("MeepMeep can't handle an action of type ${segment?.javaClass?.simpleName}")
                }

                drive.poseEstimate = pose

                actionEntity!!.markerEntityList.forEach { if (trajectorySequenceElapsedTime >= it.time) it.passed() }

                sliderMaster?.reportProgress(sliderMasterIndex ?: -1, trajectorySequenceElapsedTime)

                Unit
            }

            looping -> {
                actionEntity!!.markerEntityList.forEach {
                    it.reset()
                }
                trajectorySequenceElapsedTime = 0.0

                sliderMaster?.reportDone(sliderMasterIndex ?: -1)
            }

            else -> {
                trajectorySequenceElapsedTime = 0.0
                running = false
//                currentTrajectorySequence = null

                sliderMaster?.reportDone(sliderMasterIndex ?: -1)
            }
        }.exhaustive
    }

    fun start() {
        running = true
        trajectorySequenceElapsedTime = 0.0
    }

    fun resume() {
        running = true
    }

    fun pause() {
        trajectoryPaused = true
    }

    fun unpause() {
        trajectoryPaused = false
    }

    fun setTrajectoryProgressSeconds(seconds: Double) {
        if (currentAction != null)
            trajectorySequenceElapsedTime = min(seconds, actionTimeline(currentAction!!).first)
    }

    fun runAction(action: Action) {
        currentAction = action

        actionEntity = ActionEntity(meepMeep, action, colorScheme)
    }

    fun setConstraints(constraints: Constraints) {
        this.constraints = constraints

        drive = DriveShim(driveTrainType, constraints, pose)
    }

    fun setDriveTrainType(driveTrainType: DriveTrainType) {
        this.driveTrainType = driveTrainType

        drive = DriveShim(driveTrainType, constraints, pose)
    }

    override fun switchScheme(scheme: ColorScheme) {
        if (listenToSwitchThemeRequest)
            super.switchScheme(scheme)
    }

    fun setTrajectoryProgressSliderMaster(master: TrajectoryProgressSliderMaster, index: Int) {
        sliderMaster = master
        sliderMasterIndex = index
    }

    override fun onAddToEntityList() {
        if (actionEntity != null)
            meepMeep.requestToAddEntity(actionEntity!!)
    }

    override fun onRemoveFromEntityList() {
        if (actionEntity != null)
            meepMeep.requestToRemoveEntity(actionEntity!!)
    }
}