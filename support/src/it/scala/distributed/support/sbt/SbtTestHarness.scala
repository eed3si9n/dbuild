package com.typesafe.dbuild.support.sbt

import java.io.File
import java.net.URL

object SbtTestHarness {

  private case class PredefRepo(id: xsbti.Predefined) extends xsbti.PredefinedRepository
  private val defaultIvyPatterns = "[organization]/[module]/(scala_[scalaVersion]/)(sbt_[sbtVersion]/)[revision]/[type]s/[artifact](-[classifier]).[ext]"
  private case class IvyRepo(id: String,
                             url: URL,
                             ivyPattern: String = defaultIvyPatterns,
                             artifactPattern: String = defaultIvyPatterns,
                             mavenCompatible: Boolean = false) extends xsbti.IvyRepository
  private case class MvnRepo(id: String, url: URL) extends xsbti.MavenRepository
  private val fallBackResolvers: List[xsbti.Repository] =
    List(
      PredefRepo(xsbti.Predefined.Local),
      PredefRepo(xsbti.Predefined.MavenCentral),
      MvnRepo("typeasfe-mvn-releases", new java.net.URL("http://typesafe.artifactoryonline.com/typesafe/releases")),
      IvyRepo("typesafe-ivy-releases", new java.net.URL("http://typesafe.artifactoryonline.com/typesafe/releases")),
      IvyRepo("dbuild-snapshots", new java.net.URL("http://typesafe.artifactoryonline.com/typesafe/temp-distributed-build-snapshots"))
    )


  /** Helper which constructs an sbt build system for use. */
  def withBuildSystem[A](f: SbtTestConfig => A): A =
    // TODO - maybe use the same directory each time.
    sbt.IO.withTemporaryDirectory { dir =>
      // TODO - Figure out repositories!
      val system = new SbtBuildSystem(fallBackResolvers, dir, true)
      val projectDir = new File(dir, "test-project")
      sbt.IO.createDirectory(projectDir)
      f(SbtTestConfig(system, projectDir))
    }


  /** Writes build properties file for sbt in the given root directory. */
  def writeBuildProperties(dir: File, sbtVersion: String): Unit =
    sbt.IO.write(new File(dir, "project/build.properties"), """sbt.version="""+sbtVersion)


  def writePluginsFile(dir: File, name: String = "plugins.sbt")(content: String): Unit =
    sbt.IO.write(new File(dir, "project/" + name), content)

  def writeBuildFile(dir: File, name: String = "build.sbt")(content: String): Unit =
    sbt.IO.write(new File(dir, name), content)
}

case class SbtTestConfig(system: SbtBuildSystem, projectRootDir: File)