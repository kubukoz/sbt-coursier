package coursier.lmcoursier

import java.io.{File, OutputStreamWriter}

import _root_.coursier.{Artifact, Cache, CachePolicy, FileError, Organization, Resolution, TermDisplay, organizationString}
import _root_.coursier.core.{Classifier, Configuration, ModuleName}
import _root_.coursier.extra.Typelevel
import _root_.coursier.ivy.IvyRepository
import _root_.coursier.lmcoursier.Inputs.withAuthenticationByHost
import sbt.internal.librarymanagement.IvySbt
import sbt.librarymanagement._
import sbt.util.Logger

class CoursierDependencyResolution(conf: CoursierConfiguration) extends DependencyResolutionInterface {

  /*
   * Based on earlier implementations by @leonardehrenfried (https://github.com/sbt/librarymanagement/pull/190)
   * and @andreaTP (https://github.com/sbt/librarymanagement/pull/270), then adapted to the code from the former
   * sbt-coursier, that was moved to this module.
   */

  private def sbtBinaryVersion = "1.0"

  lazy val resolvers =
    if (conf.reorderResolvers)
      ResolutionParams.reorderResolvers(conf.resolvers)
    else
      conf.resolvers

  private lazy val excludeDependencies = conf
    .excludeDependencies
    .map {
      case (strOrg, strName) =>
        (Organization(strOrg), ModuleName(strName))
    }
    .toSet

  def moduleDescriptor(moduleSetting: ModuleDescriptorConfiguration): CoursierModuleDescriptor =
    CoursierModuleDescriptor(moduleSetting, conf)

  def update(
    module: ModuleDescriptor,
    configuration: UpdateConfiguration,
    uwconfig: UnresolvedWarningConfiguration,
    log: Logger
  ): Either[UnresolvedWarning, UpdateReport] = {

    // TODO Take stuff in configuration into account? uwconfig too?

    val module0 = module match {
      case c: CoursierModuleDescriptor =>
        // seems not to happen, not sure what DependencyResolutionInterface.moduleDescriptor is for
        c.descriptor
      case i: IvySbt#Module =>
        i.moduleSettings match {
          case d: ModuleDescriptorConfiguration => d
          case other => sys.error(s"unrecognized module settings: $other")
        }
      case _ =>
        sys.error(s"unrecognized ModuleDescriptor type: $module")
    }

    val so = conf.scalaOrganization.map(Organization(_))
      .orElse(module0.scalaModuleInfo.map(m => Organization(m.scalaOrganization)))
      .getOrElse(org"org.scala-lang")
    val sv = conf.scalaVersion
      .orElse(module0.scalaModuleInfo.map(_.scalaFullVersion))
      // FIXME Manage to do stuff below without a scala version?
      .getOrElse(scala.util.Properties.versionNumberString)

    val sbv = module0.scalaModuleInfo.map(_.scalaBinaryVersion).getOrElse {
      sv.split('.').take(2).mkString(".")
    }

    val verbosityLevel = 0

    val ttl = Cache.defaultTtl
    val createLogger = conf.createLogger.map(_.create).getOrElse { () =>
      new TermDisplay(new OutputStreamWriter(System.err), fallbackMode = true)
    }
    val cache = conf.cache.getOrElse(Cache.default)
    val cachePolicies = CachePolicy.default
    val checksums = Cache.defaultChecksums
    val projectName = "" // used for logging only…

    val ivyProperties = ResolutionParams.defaultIvyProperties()

    val classifiers =
      if (conf.hasClassifiers)
        Some(conf.classifiers.map(Classifier(_)))
      else
        None

    val authenticationByRepositoryId = conf.authenticationByRepositoryId.toMap

    val mainRepositories = resolvers
      .flatMap { resolver =>
        FromSbt.repository(
          resolver,
          ivyProperties,
          log,
          authenticationByRepositoryId.get(resolver.name)
        )
      }
      .map(withAuthenticationByHost(_, conf.authenticationByHost.toMap))

    val globalPluginsRepos =
      for (p <- ResolutionParams.globalPluginPatterns(sbtBinaryVersion))
        yield IvyRepository.fromPattern(
          p,
          withChecksums = false,
          withSignatures = false,
          withArtifacts = false
        )

    val interProjectRepo = InterProjectRepository(conf.interProjectDependencies)

    val internalRepositories = globalPluginsRepos :+ interProjectRepo

    val dependencies = module0
      .dependencies
      .flatMap { d =>
        // crossVersion already taken into account, wiping it here
        val d0 = d.withCrossVersion(CrossVersion.Disabled())
        FromSbt.dependencies(d0, sv, sbv)
      }
      .map {
        case (config, dep) =>
          val dep0 = dep.copy(
            exclusions = dep.exclusions ++ excludeDependencies
          )
          (config, dep0)
      }

    val configGraphs = Inputs.ivyGraphs(
      Inputs.configExtends(module0.configurations)
    )

    val typelevel = so == Typelevel.typelevelOrg

    val resolutionParams = ResolutionParams(
      dependencies = dependencies,
      fallbackDependencies = conf.fallbackDependencies,
      configGraphs = configGraphs,
      autoScalaLib = conf.autoScalaLibrary,
      mainRepositories = mainRepositories,
      parentProjectCache = Map.empty,
      interProjectDependencies = conf.interProjectDependencies,
      internalRepositories = internalRepositories,
      userEnabledProfiles = conf.mavenProfiles.toSet,
      userForceVersions = Map.empty,
      typelevel = typelevel,
      so = so,
      sv = sv,
      sbtClassifiers = false,
      parallelDownloads = conf.parallelDownloads,
      projectName = projectName,
      maxIterations = conf.maxIterations,
      createLogger = createLogger,
      cache = cache,
      cachePolicies = cachePolicies,
      ttl = ttl,
      checksums = checksums
    )

    def artifactsParams(resolutions: Map[Set[Configuration], Resolution]) =
      ArtifactsParams(
        classifiers = classifiers,
        res = resolutions.values.toSeq,
        includeSignatures = false,
        parallelDownloads = conf.parallelDownloads,
        createLogger = createLogger,
        cache = cache,
        artifactsChecksums = checksums,
        ttl = ttl,
        cachePolicies = cachePolicies,
        projectName = projectName,
        sbtClassifiers = false
      )

    val sbtBootJarOverrides = SbtBootJars(
      conf.sbtScalaOrganization.fold(org"org.scala-lang")(Organization(_)),
      conf.sbtScalaVersion.getOrElse(sv),
      conf.sbtScalaJars
    )

    val configs = Inputs.coursierConfigurations(module0.configurations)

    def updateParams(
      resolutions: Map[Set[Configuration], Resolution],
      artifacts: Map[Artifact, Either[FileError, File]]
    ) =
      UpdateParams(
        shadedConfigOpt = None,
        artifacts = artifacts,
        classifiers = classifiers,
        configs = configs,
        dependencies = dependencies,
        res = resolutions,
        ignoreArtifactErrors = false,
        includeSignatures = false,
        sbtBootJarOverrides = sbtBootJarOverrides
      )

    val e = for {
      resolutions <- ResolutionRun.resolutions(resolutionParams, verbosityLevel, log)
      artifactsParams0 = artifactsParams(resolutions)
      artifacts <- ArtifactsRun.artifacts(artifactsParams0, verbosityLevel, log)
      updateParams0 = updateParams(resolutions, artifacts)
      updateReport <- UpdateRun.update(updateParams0, verbosityLevel, log)
    } yield updateReport

    e.left.map(unresolvedWarningOrThrow(uwconfig, _))
  }

  private def resolutionException(ex: ResolutionError): Either[Throwable, ResolveException] =
    ex match {
      case e: ResolutionError.MetadataDownloadErrors =>
        val r = new ResolveException(
          e.errors.flatMap(_._2),
          e.errors.map {
            case ((mod, ver), _) =>
              ModuleID(mod.organization.value, mod.name.value, ver)
                .withExtraAttributes(mod.attributes)
          }
        )
        Right(r)
      case _ => Left(ex.exception())
    }

  private def unresolvedWarningOrThrow(
    uwconfig: UnresolvedWarningConfiguration,
    ex: ResolutionError
  ): UnresolvedWarning =
    resolutionException(ex) match {
      case Left(t) => throw t
      case Right(e) =>
        UnresolvedWarning(e, uwconfig)
    }

}

object CoursierDependencyResolution {
  def apply(configuration: CoursierConfiguration): DependencyResolution =
    DependencyResolution(new CoursierDependencyResolution(configuration))
}