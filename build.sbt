import Dependencies._
import RemoteDepHelper._
import SbtSupport._

def MyVersion: String = "0.9.20"

ThisBuild / scalaVersion := scala212

// keep Maven Central happy
ThisBuild / developers := List(
  Developer(
    id = "cunei",
    name = "Antonio Cunei",
    email = "antonio.cunei@typesafe.com",
    url = url("https://www.lightbend.com")
  ),
  Developer(
    id = "SethTisue",
    name = "Seth Tisue",
    email = "seth.tisue@lightbend.com",
    url = url("https://www.lightbend.com")
  ))
ThisBuild / homepage := Some(url("http://lightbend-labs.github.io/dbuild/"))
ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/lightbend-labs/dbuild"),
    "scm:git:git@github.com:lightbend-labs/dbuild.git")
)

ThisBuild / sbtLaunchJarLocation := (ThisBuild / baseDirectory).value / "target" / "sbt" / "sbt-launch.jar"

ThisBuild / sbtLaunchJar := {
  val cp = (sbtLauncherProj / Compile / externalDependencyClasspath).value
  val location = (ThisBuild / sbtLaunchJarLocation).value
  cp.map(_.data).find(_.getName.startsWith("sbt-launch")) match {
    case Some(x) =>
      IO.copyFile(x, location)
      Seq(location)
    case None    => sys.error("failed to resolve sbt-launch")
  }
}
ThisBuild / organization := "com.typesafe.dbuild"
ThisBuild / licenses += License.Apache2
ThisBuild / version := MyVersion
ThisBuild / resolvers += Resolver.typesafeIvyRepo("releases")
ThisBuild / resolvers += "Typesafe Repository" at "https://repo.typesafe.com/typesafe/releases/"

def skip210 = Seq(
  (compile / skip) := scalaVersion.value.startsWith("2.10"),
  publish := Def.taskDyn {
    val p = publish.taskValue
    if (scalaVersion.value.startsWith("2.10")) Def.task {} else Def.task(p.value)
  }.value,
  publishLocal := Def.taskDyn {
    val p = publishLocal.taskValue
    if (scalaVersion.value.startsWith("2.10")) Def.task {} else Def.task(p.value)
  }.value,
  Compile / doc / sources := {
    val theSources = (Compile / doc / sources).value
    if ((compile / skip).value) List() else theSources
  }
)

def crossBuildForPlugins =
  crossScalaVersions := Vector(scala210, scala212)

def selectSbtVersion =
  (pluginCrossBuild / sbtVersion) := {
    scalaBinaryVersion.value match {
      case "2.10" => "0.13.18"
      case "2.12" => "1.13.0"
      case _      => "2.0.7"
    }
  }

def selectScalaVersion =
  scalaVersion := {
    val sb = (pluginCrossBuild / sbtVersion).value
    if (sb.startsWith("0.13")) "2.10.7" else "2.12.21"
  }

lazy val root = (project in file("."))
  .dependsOnRemote(specs2 _, jline)
  .aggregate(adapter, graph, hashing, logging, actorLogging, proj, actorProj, deploy, http,
            core, plugin, build, support, supportGit, repo, metadata, docs, dist, indexmeta)
  .settings(
    publish / skip := true,
    publishLocal / skip := true,
    crossScalaVersions := Nil,
  )
  .settings(crossSbtVersions := Seq("0.13.18", "1.13.0"), selectScalaVersion)

// This subproject only has dynamically
// generated source files, used to adapt
// the source file to sbt 0.13/1.0
lazy val adapter = project
  .dependsOnRemote(specs2 _, jline)
  .dependsOnSbtProvided(sbtLogging, sbtIo, sbtLauncherInt, sbtIvy, sbtSbt)
  .dependsOnSbtProvidedOpt(zincIf212 _)
  .settings(
    crossBuildForPlugins,
    selectSbtVersion,
    Compile / sourceGenerators += task {
      val dir = (Compile / sourceManaged).value
      val fileName = "Default.scala"
      val file = dir / fileName
      val sv = scalaVersion.value
      val v = (pluginCrossBuild / sbtVersion).value
      if(!dir.isDirectory) dir.mkdirs()
      IO.write(file, (
"""
package com.typesafe.dbuild.adapter
object Defaults {
  val version = "%s"
  val org = "%s"
  val hash = "%s"
  val scalaVersion = "%s"
  val sbtVersion = "%s"
}
""" format (version.value, organization.value,
  scala.sys.process.Process("git log --pretty=format:%H -n 1").lines.head, sv, v))

)
    Seq(file)
  })

lazy val graph = project
  .dependsOnRemote(specs2 _, jline)
  .settings(
    crossBuildForPlugins,
    selectSbtVersion,
  )

lazy val hashing = project
  .dependsOnRemote(specs2 _, jline)
  .dependsOnRemote(typesafeConfig)
  .settings(
    crossBuildForPlugins,
    selectSbtVersion,
  )

lazy val indexmeta = project
  .dependsOnRemote(specs2 _, jline)
  .settings(
    crossBuildForPlugins,
    selectSbtVersion,
  )

lazy val logging = project
  .dependsOnRemote(specs2 _, jline)
  .dependsOn(adapter,graph)
  .dependsOnSbtProvided(sbtLogging, sbtIo, sbtCommand, sbtLauncherInt)
  .settings(
    crossBuildForPlugins,
    selectSbtVersion,
  )

lazy val actorLogging = project
  .dependsOnRemote(specs2 _, jline)
  .dependsOn(logging)
  .dependsOnRemote(akkaActor _)
  .dependsOnSbtProvided(sbtLogging, sbtIo, sbtLauncherInt)
  .settings(
    skip210,
    selectSbtVersion,
  )

lazy val metadata = project
  .dependsOnRemote(specs2 _, jline)
  .dependsOn(graph, hashing, indexmeta, deploy)
  .dependsOnRemote(jackson, typesafeConfig, commonsLang, jacks)
  .settings(
    crossBuildForPlugins,
    selectSbtVersion,
    scalaXmlAlways,
  )

lazy val repo = project
  .dependsOn(http, adapter, metadata, logging)
  .dependsOnRemote(mvnAether, aether, aetherApi, aetherSpi, aetherUtil, aetherImpl, aetherConnectorBasic, aetherFile, aetherHttp, aetherWagon, mvnAether)
  .dependsOnRemote(specs2 _, jline)
  .dependsOnSbtProvided(sbtIo, sbtLauncherInt, sbtLogging, sbtSbt)
  .settings(
    crossBuildForPlugins,
    selectSbtVersion,
    scalaXmlAlways,
  )

lazy val http = project
  .dependsOn(adapter)
  .dependsOnRemote(specs2 _, jline)
  .dependsOnRemote(dispatch _)
  .dependsOnSbtProvided(sbtIo, sbtIvy, sbtSbt)
  .settings(
    crossBuildForPlugins,
    selectSbtVersion,
    scalaXmlAlways,
  )

lazy val core = project
  .dependsOnRemote(specs2 _, jline)
  .dependsOnRemote(javaMail)
  .dependsOn(adapter,metadata, graph, hashing, logging, repo)
  .dependsOnSbtProvided(sbtIo, sbtLogging)
  .settings(
    crossBuildForPlugins,
    selectSbtVersion,
    scalaXmlAlways,
  )

lazy val proj = project
  .dependsOnRemote(specs2 _, jline)
  .dependsOn(core, repo, logging)
  .dependsOnRemote(javaMail, commonsIO)
  .dependsOnSbtProvided(sbtIo, sbtIvy, sbtSbt, sbtLogging, sbtLauncherInt)
  .settings(
    crossBuildForPlugins,
    selectSbtVersion,
    scalaXmlAlways,
  )

lazy val actorProj = project
  .dependsOnRemote(specs2 _, jline)
  .dependsOn(core, actorLogging, proj)
  .dependsOnSbtProvided(sbtIo, sbtIvy)
  .settings(
    skip210,
    selectSbtVersion,
    scalaXmlAlways,
  )

lazy val support = project
  .configs(IntegrationTest)
  .dependsOn(core, repo, metadata, proj % "compile->compile;it->compile", logging % "it")
  .dependsOnRemote(specs2 _, specs2It _, jline)
  .dependsOnRemote(mvnEmbedder, mvnWagon, javaMail, aether, aetherApi, aetherSpi, aetherUtil,
                  aetherImpl, aetherConnectorBasic, aetherFile, aetherHttp, slf4jSimple, ivy)
  .dependsOnSbtProvided(sbtLauncherInt, sbtIvy)
  .dependsOnSbtProvidedIt(sbtSbt)
  .settings(Defaults.itSettings)
  .settings(SbtSupport.buildSettings:_*)
  .settings(SbtSupport.settings:_*)
  .settings(
    // We hook the testLoader of it to make sure all the it tasks have a legit sbt plugin to use.
    // Technically, this just pushes every project.  We could outline just the plugin itself, but for now
    // we don't care that much.
    IntegrationTest / testLoader := {
      val ignore = publishLocal.all(ScopeFilter(inAggregates(LocalRootProject, includeRoot=false))).value
      (IntegrationTest / testLoader).value
    },
    IntegrationTest / parallelExecution := false,
    crossBuildForPlugins,
    selectSbtVersion,
    scalaXmlAlways,
  )

// A separate support project for git/jgit
lazy val supportGit = project
  .dependsOnRemote(specs2 _, jline)
  .dependsOn(core, repo, metadata, proj, support)
  .dependsOnRemote(mvnEmbedder, mvnWagon, javaMail, jgit)
  .dependsOnSbtProvided(sbtLauncherInt, sbtIvy)
  .settings(
    skip210,
    selectSbtVersion,
    scalaXmlAlways,
  )

// sbt plugin
lazy val plugin = project
  .enablePlugins(SbtPlugin)
  .dependsOnRemote(specs2 _, jline)
  .dependsOn(adapter, support, metadata)
  .dependsOnRemote(oro)
  .dependsOnSbtProvided(sbtIo)
  .settings(
    crossBuildForPlugins,
    selectSbtVersion,
    scalaXmlAlways,
  )

lazy val dist = (project in file("dist"))
  .dependsOnRemote(specs2 _, jline)
  .enablePlugins(UniversalPlugin)
  .settings(Packaging.settings(build,repo):_*)
  .settings(
    skip210,
    selectSbtVersion,
    scalaXmlAlways,
  )

lazy val deploy = project
  .dependsOn(adapter, http)
  .dependsOnRemote(specs2 _, jline)
  .dependsOnRemote(jackson, typesafeConfig, commonsLang, aws, uriutil, commonsIO, jsch, jacks)
  .dependsOnSbtProvided(sbtLogging, sbtIo, sbtSbt)
  .settings(
    crossBuildForPlugins,
    selectSbtVersion,
    scalaXmlAlways,
  )

lazy val build = project
  .dependsOnRemote(specs2 _, specs2It _, jline)
  .configs(IntegrationTest)
  .dependsOn(actorProj, support, supportGit, repo, metadata, deploy, proj)
  .dependsOnRemote(aws, uriutil, jsch, oro, scallop, commonsLang)
  .dependsOnRemote(gpgLibIf210 _)
  .dependsOnSbt(sbtLauncherInt, sbtLogging, sbtIo, sbtIvy, sbtSbt)
  .settings(Defaults.itSettings)
  .settings(
    skip210,
    selectSbtVersion,
    scalaXmlAlways,
    SbtSupport.settings,
    // We hook the testLoader of it to make sure all the it tasks have a legit sbt plugin to use.
    // Technically, this just pushes every project.  We could outline just the plugin itself, but for now
    // we don't care that much.
    IntegrationTest / testLoader := {
      val ignore = publishLocal.all(ScopeFilter(inAggregates(LocalRootProject, includeRoot=false))).value
      (IntegrationTest / testLoader).value
    },
    IntegrationTest / parallelExecution := false,
    scalaXmlAlways,
  )

lazy val docs = project
  .dependsOnRemote(specs2 _, jline)
  .enablePlugins(GhpagesPlugin, SphinxPlugin)
  .settings(
    Sphinx / generatePdf / enableOutput := false,
    Sphinx / generateEpub / enableOutput := false,
    git.remoteRepo := "git@github.com:lightbend/dbuild.git",
    ghpagesSynchLocal := {
      val maps = ((ghpagesSynchLocal / mappings)).value
      val repo = ghpagesUpdatedRepository.value
      val v = version.value
      val snap = isSnapshot.value
      val log = streams.value.log
      DocsSupport.synchLocalImpl(maps, repo, v, snap, log)
    },
    publish / skip := true,
    publishLocal / skip := true,
    compile / skip := true,
    makeSite := makeSite.dependsOn(sbt.Def.task {
      val file = ((Sphinx / siteSourceDirectory)).value / "version.py"
      IO.write(file, ("release = '%s'\n" format (version.value)))
    }).value
  )

lazy val sbtLauncherProj = (project in file("sbt-launcher"))
  .dependsOnRemote(specs2 _, jline)
  .settings(
    autoScalaLibrary := false,
    libraryDependencies += ("org.scala-sbt" % "sbt-launch" % "1.6.2").intransitive,
    publish / skip := true,
    publishLocal / skip := true,
  )
