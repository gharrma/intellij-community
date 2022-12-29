import java.nio.file.Paths
import kotlinx.coroutines.runBlocking
import org.jetbrains.intellij.build.IdeaCommunityProperties
import org.jetbrains.intellij.build.IdeaProjectLoaderUtil
import org.jetbrains.intellij.build.impl.BuildContextImpl
import org.jetbrains.intellij.build.impl.generateProjectStructureMapping

object ProjectStructureMappingBuildTarget {
  @Suppress("RAW_RUN_BLOCKING")
  @JvmStatic
  fun main(args: Array<String>) {
    runBlocking {
      val outfile = args.singleOrNull()
                    ?: error("Expected a single argument specifying the output file path")
      val communityHome = IdeaProjectLoaderUtil.guessCommunityHome(javaClass)
      val communityRoot = communityHome.communityRoot
      val ideProperties = IdeaCommunityProperties(communityRoot)
      val buildContext = BuildContextImpl.createContext(communityHome, communityRoot, ideProperties)
      generateProjectStructureMapping(Paths.get(outfile), buildContext)
    }
  }
}
