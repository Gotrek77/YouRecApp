package yourec

/// Which video effects this build draws in preview and export. A project
/// with any other known effect is refused by its name (ProjectCoding), never
/// opened and drawn without it.
object VideoEffectRendering {
    val drawn: Set<VideoEffectID> = emptySet()
}
