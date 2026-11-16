package yourec

import kotlin.math.abs

fun recordingHistoryTests() {
    val a = SourceMedia(filename = "first.mov", duration = 764.485)
    val b = SourceMedia(filename = "last.mov", duration = 204.31554166666666)
    val project = Project("Recording undo regression")
    project.addRecording(a); project.addRecording(b)
    require(!project.canUndo && project.history.size == 1, "Recording additions are a baseline, not editing undo steps")
    require(abs(project.edit.duration - a.duration - b.duration) < 0.000001, "Takes follow one another without a gap")
    project.commit(TimelineEditing.trimming(project.edit.timeline.video[0].id, true, 2.0, project.edit, project.sources).first)
    require(project.canUndo && project.edit.clips[0].start == 2.0, "A trim is one undo step")
    project.undo(); project.undo()
    require(abs(project.edit.duration - a.duration - b.duration) < 0.000001, "Undo cannot remove the final recording")
    val branched = project.edit; branched.timeline.setVolume(-3.0, false, branched.timeline.entireSelection()); project.commit(branched)
    require(project.edit.clips.size == 2 && !project.canRedo, "A new edit after Undo retains all captured segments")
    project.undo()
    project.addRecording(SourceMedia(filename = "continued.mov", duration = 7.0))
    require(project.canRedo && project.edit.clips.size == 3, "Continuing recording retains the existing editing redo branch")
    project.redo()
    require(project.edit.timeline.audio.take(2).all { it.volumeDB == -3.0 } && project.edit.timeline.audio[2].volumeDB == 0.0 && project.edit.clips.size == 3, "Redo restores the edit while preserving continued footage")
    project.undo()
    require(project.edit.timeline.audio.all { it.volumeDB == 0.0 } && project.edit.clips.size == 3, "Older edits remain undoable after recording continuation")
    val deletion = project.edit
    project.commit(TimelineEditing.deleting(deletion.timeline.selection(deletion.timeline.video[1].id), deletion).first)
    require(project.edit.clips.none { it.sourceID == b.id }, "Explicit clip deletion still works")
    project.undo(); require(project.edit.clips.size == 3, "Undoing an explicit deletion restores the deleted clip")
    project.redo(); project.addRecording(SourceMedia(filename = "later.mov", duration = 3.0))
    require(project.edit.clips.none { it.sourceID == b.id }, "New recording does not reverse an intentional deletion")
    project.undo(); require(project.edit.clips.size == 4, "Deletion can still be undone without losing later capture")
    project.validate()

    // A draft opened before a take finished still receives that take when committed.
    val draft = project.edit
    project.addRecording(SourceMedia(filename = "during.mov", duration = 2.0))
    draft.timeline.addBookmark(EditTime.seconds(1.0))
    project.commit(draft)
    require(project.edit.clips.size == 5 && project.edit.timeline.bookmarks.size == 1, "An edit drafted during recording keeps the footage recorded meanwhile")
    project.validate()

    val unchanged = Project("No-op"); unchanged.addRecording(a)
    unchanged.commit(unchanged.edit)
    require(!unchanged.canUndo, "Committing an unchanged edit adds no history")

    // Explicit recovery only: a deleted take never returns by itself.
    val recovery = Project("Recover unused"); recovery.addRecording(a); recovery.addRecording(b)
    requireFailure<ProjectError.InvalidDocument>("A take still present in history is not an unused original") { recovery.restoreUnusedRecording(b.id) }
    val orphan = SourceMedia(filename = "orphan.mov", duration = 5.0)
    recovery.sources = recovery.sources + orphan
    recovery.restoreUnusedRecording(orphan.id)
    require(recovery.edit.clips.size == 3 && !recovery.canUndo, "An explicitly restored original joins every edit state like a recording")

    // The picture shape follows its reference take, not whichever clip is first.
    val shape = Project("Canvas"); shape.addRecording(a); shape.addRecording(b)
    val moved = shape.edit
    moved.timeline.remove(moved.timeline.selection(moved.timeline.video[0].id))
    shape.commit(moved)
    require(shape.edit.canvasSourceID == b.id && shape.edit.canvasReference == null, "With the first take gone the next one defines the shape")
    shape.undo()
    val reordered = shape.edit
    val firstID = reordered.timeline.video[0].id
    reordered.timeline.move(reordered.timeline.selection(firstID), EditTime.seconds(2000.0))
    shape.commit(reordered)
    require(shape.edit.canvasSourceID == a.id && shape.edit.canvasReference == a.id && shape.schemaVersion == 10, "Reordering keeps the original take as the shape reference")
}
