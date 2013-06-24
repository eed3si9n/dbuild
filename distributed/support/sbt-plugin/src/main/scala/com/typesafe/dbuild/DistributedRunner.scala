package com.typesafe.dbuild

import sbt._
import distributed.project.model
import distributed.support.sbt.SbtBuildConfig
import distributed.project.model.ArtifactLocation
import distributed.project.model.Utils.{ writeValue, readValue }
import StateHelpers._
import DistributedBuildKeys._
import NameFixer.fixName
import java.io.File
import distributed.repo.core.LocalArtifactMissingException

object DistributedRunner {

  // TODO - Config helper!
  def isValidProject(projects: Seq[String], ref: ProjectRef): Boolean =
    projects.isEmpty || (projects exists (_ == ref.project))

  def timed[A](f: => Stated[A]): Stated[Long] = {
    val start = java.lang.System.currentTimeMillis
    val result = f
    val end = java.lang.System.currentTimeMillis
    result map (_ => (end - start))
  }

  def averageOf[A](n: Int)(state: Stated[A])(f: Stated[Long] => Stated[Long]): (Stated[Double]) = {
    val result = (state.of(0L) /: (0 to n).toSeq) { (state, _) =>
      val prev = state.value
      timed(f(state)) map (_ + prev)
    }
    result map (_ / n.toDouble)
  }

  def timedBuildProject(ref: ProjectRef, state: State): (State, (String,ArtifactMap)) = {
    println("Running timed build: "+ref.project)
    val x = Stated(state)
    def cleanBuild(state: Stated[_]) = {
      val cleaned = state runTask Keys.clean
      timed(cleaned runTask (Keys.compile in Compile))
    }
    val perf = averageOf(10)(x)(cleanBuild)
    val y = perf.runTask(extractArtifacts in ref)
    val arts = y.value map (_.copy(buildTime = perf.value))
    (y.state, ref.project -> arts)
  }
  def untimedBuildProject(ref: ProjectRef, state: State): (State, (String,ArtifactMap)) = {
    println("Running build: "+ref.project)
    val y = Stated(state).runTask(extractArtifacts in ref)
    (y.state, ref.project -> y.value)
  }

  // TODO - Use a specific key that allows posting other kinds of artifacts.
  // Maybe also use a platform-specific task such that we can expose
  // windows artifacts on windows, etc.
  def buildProject(state: State, config: SbtBuildConfig): (State, Seq[(String,ArtifactMap)]) = {
    println("Building project")
    // Stage half the computation, including what we're churning through.
    val buildAggregate = runAggregate[Seq[(String,ArtifactMap)],(String,ArtifactMap)](state, config, Seq.empty)(_ :+ _) _
    // If we're measuring, run the build several times.
    if (config.config.measurePerformance) buildAggregate(timedBuildProject)
    else buildAggregate(untimedBuildProject)
  }

  /** Runs a series of commands across projects, aggregating results. */
  private def runAggregate[Q,T](state: State, config: SbtBuildConfig, init: Q)(merge: (Q, T) => Q)(f: (ProjectRef, State) => (State, T)): (State, Q) = {
    val extracted = Project.extract(state)
    import extracted._
    val refs = getProjectRefs(extracted)
    // this is the list of projects calculated in DependencyAnalysis;
    // conversely, config.config.projects is the list specified in the
    // configuration file (in the "extra" section)
    val projects=config.info.subproj
    verifySubProjects(projects, refs)
    refs.foldLeft[(State, Q)](state -> init) {
      case ((state, current), ref) =>
        if (isValidProject(projects, ref)) {
          val (state2, next) =
            f(ref, state)
          state2 -> merge(current, next)
        } else state -> current // TODO - if a project listed in the build does not exist, or list empty, it should really abort
    }
  }

  // verify that the requested projects in SbtBuildConfig actually exist
  def verifySubProjects(requestedProjects: Seq[String], refs: Seq[sbt.ProjectRef]): Unit = {
    if (requestedProjects.nonEmpty) {
      val uniq=requestedProjects.distinct
      if (uniq.size != requestedProjects.size) {
        sys.error("Some subprojects are listed twice: " + (requestedProjects.diff(uniq)).mkString("\"", "\", \"", "\"."))
      }
      val availableProjects = refs.map(_.project)
      val notAvailable = requestedProjects.toSet -- availableProjects
      if (notAvailable.nonEmpty)
        sys.error("These subprojects were not found: " + notAvailable.mkString("\"", "\", \"", "\"."))
    }
  }

  def makeBuildResults(artifacts: Seq[(String,ArtifactMap)], localRepo: File): model.BuildArtifactsOut =
    model.BuildArtifactsOut(artifacts, localRepo)

  def printResults(fileName: String, artifacts: Seq[(String,ArtifactMap)], localRepo: File): Unit =
    IO.write(new File(fileName), writeValue(makeBuildResults(artifacts, localRepo)))

  def loadBuildConfig: Option[SbtBuildConfig] =
    for {
      f <- Option(System getProperty "project.build.deps.file") map (new File(_))
      deps = readValue[SbtBuildConfig](f)
    } yield deps

  // TODO - Here we rely on a sequence of artifact locations, and we try to do
  // the matching manually. Ideally, we should take our ModuleID, point Ivy to
  // the rematerialized repository, and ask Ivy whether the module can be
  // resolved against that repository. It requires a bit of code, but would
  // be somewhat more general, at least in principle.
  def fixModule(arts: Seq[model.ArtifactLocation])(m: ModuleID): ModuleID = {
      def expandName(a:Artifact) = {
        import a._
        classifier match {
          case None => fixName(name)
          case Some(clas) => fixName(name)+"-"+clas
        }
      }
      def findArt: Option[model.ArtifactLocation] =
      (for {
        artifact <- arts.view
        if artifact.info.organization == m.organization
        if artifact.info.name == fixName(m.name) || (m.explicitArtifacts map expandName).contains(artifact.info.name)
      } yield artifact).headOption
    findArt map { art =>
      // println("Updating: " + m + " to: " + art)
      // TODO - Update our publishing so we don't have a cross versions too.....
      // TODO - warning: cross-version settings should probably
      // /not/ be changed in case a new scala version is not specified
      // (in case scala is not part of the dbuild project file)
      if (m.explicitArtifacts.isEmpty)
        m.copy(name = art.info.name, revision = art.version, crossVersion = CrossVersion.Disabled)
      else
        m.copy(revision = art.version, crossVersion = CrossVersion.Disabled)
    } getOrElse m
  }

  def inNScopes(n:Int) = if(n==1) "in one scope" else "in "+n+" scopes"
  
  def fixPublishTos2(repoDir: File)(oldSettings: Seq[Setting[_]], log: Logger): Seq[Setting[_]] = {
    val name = "deploy-to-local-repo"
    val mavenRepo = Some(Resolver.file(name, repoDir)(Resolver.mavenStylePatterns))
    val ivyRepo = Some(Resolver.file(name, repoDir)(Resolver.ivyStylePatterns))

    // The process is a bit tricky. Consider the following scenario (really occurring in sbt):
    // - publishTo in ThisBuild: (...something... or None!...)
    // - publishMavenStyle in ThisBuild: true
    // - publishMavenStyle in project: false
    //
    // In this case, the project would see the publishTo above, and publishMavenStyle false. Yet, the
    // two are in two different scopes, and it would not be obvious how to fix the settings just by
    // looking at the only publishTo: checking the publishMavenStyle in the same scope would be misleading.
    //
    // I cannot "delete" existing publishTo repositories, unfortunately, therefore I am trying the
    // following approach:
    // 1) find lastSettings by publishTo. Change them according to their own format: PatternBased with
    // isMavenCompatible=false -> Ivy, else Maven. If None, I still go for Maven, at this point.
    // 2) scan according to publishMavenStyle, add a publishTo to each scope accordingly
    // 3) if no publishTo and no publishMavenStyle, add a default Maven publishTo & publishMavenStyle
    // (but there should always be a default publishMavenStyle in sbt)
    //
    // The mess above should do the trick, at least in theory.

    val ptSettings = lastSettingsByScope(oldSettings, Keys.publishTo)
    if (ptSettings.nonEmpty)
      log.info("Updating publishTo repo " + inNScopes(ptSettings.length))

    val newSettings1 = {
      ptSettings map { s =>
        Project.update(s.asInstanceOf[Setting[Option[sbt.Resolver]]].key) {
          _ match {
            case Some(r: PatternsBasedRepository) if (!r.patterns.isMavenCompatible) => ivyRepo
            case _ => mavenRepo
          }
        }
      }
    }

    val pmsSettings = lastSettingsByScope(oldSettings ++ newSettings1, Keys.publishMavenStyle)
    if (pmsSettings.nonEmpty)
      log.info("Found publishMavenStyle " + inNScopes(pmsSettings.length) + "; changing publishTo settings accordingly.")

    val newSettings = newSettings1 ++ {
      pmsSettings map { s =>
        val sc = s.key.scope
        Keys.publishTo in sc <<= (Keys.publishMavenStyle in sc) { if (_) mavenRepo else ivyRepo }
      }
    }

    if (newSettings.isEmpty) {
      log.info("No publishTo or publishMavenStyle settings found; adding a default Maven publishTo.")
      Seq(Keys.publishTo in ThisBuild := mavenRepo,
        Keys.publishMavenStyle in ThisBuild := true)
    } else newSettings
  }

  // the "...2" routines generate a list of new settings, typically one per scope, that are tacked at the end of the current
  // ones; the sbt session is also patched accordingly once the full list of additional new settings is known. 

  // fixDependencies2(), for example:
  // Generates a list of additional settings that can be tacked onto the current list of settings in order to
  // adapt dependencies in order to reflect the new values.
  // Note: the "libraryDependencies" setting is usually present in multiple places in the list of settings; each one may
  // modify the list (usually adding further dependencies). Hence, it would be unnecessary to create a new setting patching
  // each occurrence: duplicates and other weirdness may result.
  // We only inspect the most recent setting for each scope, adding a rewriting to that last one. In case "dbuild-setup" is
  // called multiple times, each will patch the previous dbuild one, which is however ok as we replace rather than adding;
  // ideally a "reload" should precede "dbuild-setup", however.
  //

  // Collect the last settings in each scope that (re)define a given key
  private def lastSettingsByScope(oldSettings: Seq[Setting[_]], theKey: Scoped): Seq[Setting[_]] = {
    val key = theKey.key
    oldSettings.filter(_.key.key == key).groupBy(_.key.scope).map(_._2.last).toSeq
  }

  // applies a generic transformation from Setting[K] (the old one) to another Setting[K] (the new one)
  def fixGenericTransform2[K](k: Scoped)(f: Setting[K] => Setting[K])(msg: String)(oldSettings: Seq[Setting[_]], log: Logger) = {
    val lastSettings = lastSettingsByScope(oldSettings, k)
    if (lastSettings.nonEmpty) log.info(msg + " " + inNScopes(lastSettings.length))
    lastSettings.asInstanceOf[Seq[Setting[K]]] map f
  }

  // as above, but assumes the transformation is a simple Project.update (aka: ~= )
  def fixGenericK2[K](k: Scoped, f: K => K) = fixGenericTransform2(k) { s: Setting[K] => Project.update(s.key)(f) } _

  // Separate cases for settings and tasks (to keep the type inferencer happy)
  def fixGeneric2[K](k: SettingKey[K], m: String)(f: K => K) = fixGenericK2(k, f)(m)
  def fixGeneric2[K](k: TaskKey[K], m: String)(f: Task[K] => Task[K]) = fixGenericK2(k, f)(m)

  type Fixer = (Seq[Setting[_]], Logger) => Seq[Setting[_]]

  def fixCrossVersions2 =
    fixGeneric2(Keys.crossVersion, "Disabling cross versioning") { _ => CrossVersion.Disabled }

  def fixDependencies2(locs: Seq[model.ArtifactLocation]) =
    fixGeneric2(Keys.libraryDependencies, "Updating library dependencies") { old => old map fixModule(locs) }

  def fixVersions2(config: SbtBuildConfig) =
    fixGeneric2(Keys.version, "Updating version strings") { _ => config.info.version }

  def fixResolvers2(dbuildRepoDir: File) =
    fixGeneric2(Keys.resolvers, "Adding resolvers to retrieve build artifacts") { old =>
      // make sure to add our resolvers at the beginning!
      Seq(
        "dbuild-local-repo-maven" at ("file:" + dbuildRepoDir.getAbsolutePath()),
        Resolver.file("dbuild-local-repo-ivy", dbuildRepoDir)(Resolver.ivyStylePatterns)) ++
        (old filterNot { r =>
          val n = r.name; n == "dbuild-local-repo-maven" || n == "dbuild-local-repo-ivy"
        })
    }

  // we want to match against only one and precisely one scala version; therefore any
  // binary compatibility lookup machinery must be disabled
  def fixScalaBinaryVersions2 =
    fixGenericTransform2(Keys.scalaBinaryVersion) { s: Setting[String] =>
      val sc = s.key.scope
      Keys.scalaBinaryVersion in sc <<= Keys.scalaVersion in sc
    }("Setting Scala binary version") _

  // sbt will try to check the scala binary version we use in this project (the full version,
  // including suffixes) against what Ivy reports as the version of the scala library (which is
  // a shortened version). That generates tons of warnings; in order to disable that, we set
  // IvyScala.checkExplicit to false
  def fixScalaBinaryCheck2 =
    fixGeneric2(Keys.ivyScala, "Disabling Scala binary checking") { _ map { _.copy(checkExplicit=false) } }

  // We need to disable the inter-project resolver entirely. Otherwise, sbt will try to build all
  // of the dependent subprojects each time one of the subprojects is built, including some that
  // we may have explicitly excluded (as they are built in a different project, for instance)
  def fixInterProjectResolver2 =
    fixGeneric2(Keys.projectResolver, "Disabling inter-project resolver") { _ map { _ => new RawRepository(new ProjectResolver("inter-project", Map.empty)) } }
  
  // In order to convince sbt to use the scala instance we need, we just generate a fictitious
  // "lib" directory, like the one that would be generated by ant dist, and set scalaHome to that
  // directory (see ./util/classpath/ScalaInstance.scala in sbt for details)
  // Java 6 has no symlinks facility, therefore the files need to be copied.
  //
  // repoDir is the local-repo, which should already contain the re-materialized files
  def fixScalaVersion2(dbuildDir: File, repoDir: File, locs: Seq[model.ArtifactLocation])(oldSettings: Seq[Setting[_]], log: Logger) = {
    customScalaVersion(locs).toSeq flatMap { ver =>
      val scalaHome = dbuildDir / "scala" / ver
      log.info("Preparing Scala binaries: version " + ver)
      generateScalaDir(scalaHome, repoDir, ver)
      fixGeneric2(Keys.scalaVersion, "Setting Scala version to: " + ver){ _ => ver }(oldSettings, log) ++
      fixGeneric2(Keys.scalaHome, "Setting Scala home"){ _ => Some(scalaHome) }(oldSettings, log)
    }
  }

  // get the custom scala version string, if one is present somewhere in the list of artifacts of this build  
  private def customScalaVersion(arts: Seq[distributed.project.model.ArtifactLocation]): Option[String] =
    (for {
      artifact <- arts.view
      dep = artifact.info
      if dep.organization == "org.scala-lang"
      if dep.name == "scala-library"
    } yield artifact.version).headOption

  def generateScalaDir(scalaHome: File, repoDir: File, ver: String) = {
    // sbt uses needs a small set of jars in scalaHome. I only copy those, therefore.
    val neededJars = Seq("scala-library", "scala-compiler")
    val optionalJars = Seq("scala-reflect", "jline", "fjbg")
    val org = "org.scala-lang"
    neededJars foreach retrieveJarFile(scalaHome, repoDir, org, ver, true)
    optionalJars foreach retrieveJarFile(scalaHome, repoDir, org, ver, false)
  }

  def retrieveJarFile(scalaHome: File, repoDir: File, org: String, version: String, needed: Boolean)(name: String) = {
    try IO.copyFile(jarFile(repoDir, org, name, version), scalaHome / "lib" / (name + ".jar"), false)
    catch {
      case e: Exception => if (needed)
        throw new LocalArtifactMissingException("Could not find needed jar in local repo: " + name + "-" + version + ".jar", e.getMessage)
    }
  }

  def jarFile(repoDir: File, org: String, name: String, version: String) =
    org.split('.').foldLeft(repoDir)(_ / _) / name / version / (name + "-" + version + ".jar")

  def fixPGPs2(oldSettings: Seq[Setting[_]], log: Logger) =
    fixGeneric2(Keys.skip, "Disabling PGP signing") { old => old map (_ => true) }(oldSettings.filter {
      _.key.scope.task.toOption match {
        case Some(scope) if scope.label.toString == "pgp-signer" => true
        case _ => false
      }
    }, log)

  def fixBuildSettings(config: SbtBuildConfig, state: State): State = {
    // TODO: replace with the correct logger
    val log = sbt.ConsoleLogger()
    log.info("Updating dependencies...")
    val extracted = Project.extract(state)
    import extracted._
    val dbuildDirectory = Keys.baseDirectory in ThisBuild get structure.data map (_ / ".dbuild")

    dbuildDirectory map { dbuildDir =>
      val repoDir = dbuildDir / "local-repo"

      val refs = getProjectRefs(extracted)

      def newSettings(oldSettings:Seq[Setting[_]]) =
        preparePublishSettings(config, log, oldSettings) ++
          prepareCompileSettings(log, dbuildDir, repoDir, config.info.artifacts.artifacts, oldSettings)

      newState(state, extracted, newSettings)

    } getOrElse {
      sys.error("Key baseDirectory is undefined in ThisBuild: aborting.")
      state
    }
  }

  def printResolvers(state: State): Unit = {
    println("Using resolvers:")
    val extracted = Project.extract(state)
    import extracted._
    val refs = getProjectRefs(extracted)
    for {
      ref <- refs
      (_, resolvers) = extracted.runTask(Keys.fullResolvers in ref, state)
      r <- resolvers
    } println("\t(%s) - %s" format (r.name, r.toString))
  }

  def buildStuff(state: State, resultFile: String, config: SbtBuildConfig): State = {
    // printResolvers(state)
    val state2 = fixBuildSettings(config, state)

    println("Building project...")
    val refs = getProjectRefs(Project.extract(state2))
    val projects=config.info.subproj
    verifySubProjects(projects, refs)
    val buildAggregate = runAggregate[Seq[(String,ArtifactMap)],(String,ArtifactMap)](state2, config, Seq.empty)(_ :+ _) _

    // If we're measuring, run the build several times.
    val buildTask = if (config.config.measurePerformance) timedBuildProject _ else untimedBuildProject _

    def buildTestPublish(ref: ProjectRef, state6: State): (State, (String,ArtifactMap)) = {
      val (state7,(name,artifacts)) = buildTask(ref,state6)
      println("Testing: "+ref.project)
      val (state8,_) = 
        Project.extract(state7).runTask(Keys.test in (ref, Test),state7)
      println("Publishing: "+ref.project)
      val (state9, _) =
            Project.extract(state8).runTask(Keys.publish in ref, state8)
      (state9,(name,artifacts))
    }

    val (state3,artifacts) = buildAggregate(buildTestPublish)
    
    printResults(resultFile, artifacts, config.info.outRepo)
    state3
  }

  /** The implementation of the dbuild-build command. */
  def buildCmd(state: State): State = {
    val resultFile = Option(System.getProperty("project.build.results.file"))
    val results = for {
      f <- resultFile
      config <- loadBuildConfig
    } yield buildStuff(state, f, config)
    results getOrElse state
  }

  def loadBuildArtifacts(readRepo: File, builduuid: String, thisProject: Option[String], log: Logger) = {
    import distributed.repo.core._
    val cache = Repository.default
    val uuids = (for {
      build <- LocalRepoHelper.readBuildMeta(builduuid, cache).toSeq
      allProjects = build.repeatableBuilds
      uuid <- thisProject match {
        case Some(proj) => for {
          project <- allProjects.filter(_.config.name == proj)
          _ = log.info("Retrieving dependencies for " + project.uuid + " " + project.config.name)
          uuid <- project.transitiveDependencyUUIDs
        } yield uuid
        case None => {
          log.info("Retrieving all artifacts from " + allProjects.length + " projects")
          build.repeatableBuilds map { _.uuid }
        }
      }
    } yield uuid).distinct
    LocalRepoHelper.getArtifactsFromUUIDs(log.info, cache, readRepo, uuids)
  }

  private def prepareCompileSettings(log: ConsoleLogger, dbuildDir: File, repoDir: File, publishRepoDir: File, arts: Seq[ArtifactLocation], oldSettings: Seq[Setting[_]]) =
    Seq[Fixer](
          fixResolvers2(repoDir),
          fixDependencies2(arts),
          fixScalaVersion2(dbuildDir, repoDir, arts),
          fixInterProjectResolver2,
          fixScalaBinaryVersions2,
          fixCrossVersions2,
          fixScalaBinaryCheck2) flatMap { _(oldSettings, log) }

  private def preparePublishSettings(config: SbtBuildConfig, log: ConsoleLogger, oldSettings: Seq[Setting[_]]) =
    Seq[Fixer](
        fixPublishTos2(config.info.outRepo.getAbsoluteFile),
        fixPGPs2,
        fixVersions2(config)
      ) flatMap { _(oldSettings, log) }


  private def newState(state: State, extracted: Extracted, update: Seq[Setting[_]] => Seq[Setting[_]]) = {
    import extracted._
    val oldSettings = session.mergeSettings
    val newSettings = update(oldSettings)
    // Session strings can't be replayed, but are useful for debugging
    val newSessionSettings = newSettings map (a => (a, List("// dbuild-setup: " + a.key.toString)))
    // TODO - Should we honor build transformers? See transformSettings() in sbt's "Extracted.append()"
    val newSession = session.appendSettings(newSessionSettings)
    val newStructure = Load.reapply(oldSettings ++ newSettings, structure) // ( Project.showContextKey(newSession, structure) )
    val newState = Project.setProject(newSession, newStructure, state)
    newState
  }


  def setupCmd(state: State, args: Seq[String]): State = {
    val log = sbt.ConsoleLogger()
    // TODO - here I just grab the console logger, but "last" won't work as dbuild-setup
    // is not a task. I could add a wrapper task around the command, though.

    // TODO - add help text
    if (args.length < 1 || args.length > 2) sys.error("Usage: dbuild-setup <builduuid> [<thisProjectInDsbt>]")
    val builduuid = args(0)

    // The dbuild-setup command accepts a builduuid, and optionally a string that should match the project string
    // of the current sbt project, as specified in the .dbuild project file (which may be arbitrary)
    // If specified, download the dependencies of the specified project; if not specified, download all of the
    // artifacts of all the projects listed under builduuid.
    val project = if (args.length == 1) None else Some(args(1))
    val extracted = Project.extract(state)
    import extracted._
    val dbuildDirectory = Keys.baseDirectory in ThisBuild get structure.data map (_ / ".dbuild")

    // note: we don't include config.config.directory here; the user needs to be in the
    // right subdir before entering sbt, in any case, so we should be ok
    dbuildDirectory map { dbuildDir =>
      val repoDir = dbuildDir / "local-repo"
      val arts = loadBuildArtifacts(repoDir, builduuid, project, log)
      if (arts.isEmpty) {
        log.warn("No artifacts are dependencies" + { project map (" of project " + _) getOrElse "" } + " in build " + builduuid)
        state
      } else
        newState(state, extracted, prepareCompileSettings(log, dbuildDir, repoDir, arts, _))
    } getOrElse {
      log.error("Key baseDirectory is undefined in ThisBuild: aborting.")
      state
    }
  }

  private def buildIt = Command.command("dbuild-build")(buildCmd)
  private def setItUp = Command.args("dbuild-setup", "<builduuid> [<thisProjectInDsbt>]")(setupCmd)
  // The "//" command does nothing, which is exactly what should happen if anyone tries to save and re-play the session
  private def comment = Command.args("//", "// [comments]") { (state, _) => state }

  /** Settings you can add your build to print dependencies. */
  def buildSettings: Seq[Setting[_]] = Seq(
    Keys.commands += buildIt,
    Keys.commands += setItUp,
    Keys.commands += comment)

  def extractArtifactLocations(org: String, version: String, artifacts: Map[Artifact, File]): Seq[model.ArtifactLocation] =
    for {
      (artifact, file) <- artifacts.toSeq
    } yield model.ArtifactLocation(
      model.ProjectRef(artifact.name, org, artifact.extension, artifact.classifier),
      version)

  // TODO - We need to publish too....
  def projectSettings: Seq[Setting[_]] = Seq(
    extractArtifacts <<= (Keys.organization, Keys.version, Keys.packagedArtifacts in Compile) map extractArtifactLocations)

}
