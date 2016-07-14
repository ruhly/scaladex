package ch.epfl.scala.index
package server

import model._
import release._
import misc.Pagination
import data.elastic._

import com.sksamuel.elastic4s._
import ElasticDsl._
import org.elasticsearch.search.sort.SortOrder

import scala.concurrent.{ExecutionContext, Future}
import scala.language.reflectiveCalls

class Api(github: Github)(implicit val ec: ExecutionContext) {
  private def hideId(p: Project) = p.copy(_id = None)

  val resultsPerPage: Int = 10

  val sortQuery = (sorting: Option[String]) =>
    sorting match {
      case Some("stars") => fieldSort("github.stars") missing "0" order SortOrder.DESC mode MultiMode.Avg
      case Some("forks") => fieldSort("github.forks") missing "0" order SortOrder.DESC mode MultiMode.Avg
      case Some("relevant") => scoreSort
      case Some("created") => fieldSort("created") order SortOrder.DESC
      case Some("updated") => fieldSort("updated") order SortOrder.DESC
      case _ => scoreSort
    }

  private def query(q: QueryDefinition, page: PageIndex, sorting: Option[String]): Future[(Pagination, List[Project])] = {
    val clampedPage = if(page <= 0) 1 else page
    esClient.execute {
      search
        .in(indexName / projectsCollection)
        .query(q)
        .start(resultsPerPage * (clampedPage - 1))
        .limit(resultsPerPage)
        .sort(sortQuery(sorting))
    }.map(r => (
      Pagination(
        current = clampedPage,
        totalPages = Math.ceil(r.totalHits / resultsPerPage.toDouble).toInt,
        total = r.totalHits
      ),
      r.as[Project].toList.map(hideId)
    ))
  }

  def find(queryString: String, page: PageIndex, sorting: Option[String] = None) = {
    val escaped = queryString.replaceAllLiterally("/", "\\/")
    query(
      bool(
        should(
          termQuery("keywords", escaped),
          termQuery("github.description", escaped),
          termQuery("repository", escaped),
          termQuery("organization", escaped),
          termQuery("github.readme", escaped),
          stringQuery(escaped)
        )
      ),
      page,
      sorting
    )
  }

  def releases(project: Project.Reference): Future[List[Release]] = {
    esClient.execute {
      search.in(indexName / releasesCollection).query(
        nestedQuery("reference").query(
          bool (
            must(
              termQuery("reference.organization", project.organization),
              termQuery("reference.repository", project.repository)
            )
          )
        )
      ).size(1000)
    }.map(_.as[Release].toList)
  }

  /**
   * search for a maven artifact
   * @param maven
   * @return
   */
  def maven(maven: MavenReference): Future[Option[Release]] = {

    esClient.execute {
      search.in(indexName / releasesCollection).query(
        nestedQuery("maven").query(
          bool (
            must(
              termQuery("maven.groupId", maven.groupId),
              termQuery("maven.artifactId", maven.artifactId),
              termQuery("maven.version", maven.version)
            )
          )
        )
      ).limit(1)
    }.map(r => r.as[Release].headOption)
  }

  def project(project: Project.Reference): Future[Option[Project]] = {
    esClient.execute {
      search.in(indexName / projectsCollection).query(
        bool (
          must(
            termQuery("organization", project.organization),
            termQuery("repository", project.repository)
          )
        )
      ).limit(1)
    }.map(r => r.as[Project].headOption)
  }
  def projectPage(projectRef: Project.Reference, selection: ReleaseSelection): Future[Option[(Project, Int, ReleaseOptions)]] = {
    val projectAndReleases =
      for {
        project <- project(projectRef)
        releases <- releases(projectRef)
      } yield (project, releases)

    projectAndReleases.map{ case (p, releases) =>
      p.flatMap(project =>
        DefaultRelease(project, selection, releases).map((project, releases.size, _))
      )
    }
  }

  def latestProjects() = latest[Project](projectsCollection, "created", 12).map(_.map(hideId))
  def latestReleases() = latest[Release](releasesCollection, "released", 12)

  private def latest[T: HitAs : Manifest](collection: String, by: String, n: Int): Future[List[T]] = {
    esClient.execute {
      search.in(indexName / collection)
        .query(matchAllQuery)
        .sort(fieldSort(by) order SortOrder.DESC)
        .limit(n)

    }.map(r => r.as[T].toList)
  }

  def keywords() = aggregations("keywords")
  def targets() = aggregations("targets")
  def dependencies() = {
    // we remove testing or logging because they are always a dependency
    // we could have another view to compare testing frameworks
    val testOrLogging = Set(
      "akka/akka-slf4j",
      "akka/akka-testkit",
      "etorreborre/specs2",
      "etorreborre/specs2-core",
      "etorreborre/specs2-junit",
      "etorreborre/specs2-mock",
      "etorreborre/specs2-scalacheck",
      "lihaoyi/utest",
      "paulbutcher/scalamock-scalatest-support",
      "playframework/play-specs2",
      "playframework/play-test",
      "rickynils/scalacheck",
      "scala/scala-library",
      "scalatest/scalatest",
      "scalaz/scalaz-scalacheck-binding",
      "scopt/scopt",
      "scoverage/scalac-scoverage-plugin",
      "scoverage/scalac-scoverage-runtime",
      "spray/spray-testkit",
      "typesafehub/scala-logging",
      "typesafehub/scala-logging-slf4j"
    )

    aggregations("dependencies").map(agg =>
      agg.toList.sortBy(_._2)(Descending).filter{ case (ref, _) =>
        !testOrLogging.contains(ref)
      }
    )
  }

  /**
   * list all tags including number of facets
   * @param field the field name
   * @return
   */
  private def aggregations(field: String): Future[Map[String, Long]] = {

    import scala.collection.JavaConverters._
    import org.elasticsearch.search.aggregations.bucket.terms.StringTerms

    val aggregationName = s"${field}_count"

    esClient.execute {
      search.in(indexName / projectsCollection).aggregations(
        aggregation.terms(aggregationName).field(field).size(50)
      )
    }.map( resp => {
      val agg = resp.aggregations.get[StringTerms](aggregationName)
      agg.getBuckets.asScala.toList.collect {
        case b: StringTerms.Bucket => b.getKeyAsString -> b.getDocCount
      }.toMap

    })
  }

  private def maxOption[T: Ordering](xs: List[T]): Option[T] = if(xs.isEmpty) None else Some(xs.max)
}
