package polynote.server.repository

import java.nio.charset.StandardCharsets
import java.nio.file.{FileAlreadyExistsException, FileVisitOption, Files, Path}

import scala.collection.JavaConverters._
import cats.effect.{ContextShift, IO}
import io.circe.Printer
import org.http4s.client._
import org.http4s.client.blaze._
import polynote.config.{DependencyConfigs, PolynoteConfig}
import polynote.kernel.util.OptionEither
import polynote.messages._
import polynote.server.repository.ipynb.ZeppelinNotebook

import scala.concurrent.ExecutionContext

trait NotebookRepository[F[_]] {

  def notebookExists(path: String): F[Boolean]

  def loadNotebook(path: String): F[Notebook]

  def saveNotebook(path: String, cells: Notebook): F[Unit]

  def listNotebooks(): F[List[String]]

  def createNotebook(path: String, maybeUriOrContent: OptionEither[String, String]): F[String]
}

trait FileBasedRepository extends NotebookRepository[IO] {
  def path: Path
  def chunkSize: Int
  def executionContext: ExecutionContext
  def config: PolynoteConfig

  implicit val contextShift: ContextShift[IO]

  protected def pathOf(relativePath: String): Path = path.resolve(relativePath)

  protected def loadString(path: String): IO[String] = for {
    content <- readBytes(Files.newInputStream(pathOf(path)), chunkSize, executionContext)
  } yield new String(content.toArray, StandardCharsets.UTF_8)

  def writeString(relativePath: String, content: String): IO[Unit] = IO {
    val nbPath = pathOf(relativePath)

    if (nbPath.getParent != this.path) {
      Files.createDirectories(nbPath.getParent)
    }

    Files.write(pathOf(relativePath), content.getBytes(StandardCharsets.UTF_8))
  }.map(_ => ())

  protected def defaultExtension: String

  protected def validNotebook(path: Path): Boolean = path.toString.endsWith(s".$defaultExtension")
  protected def maxDepth: Int = 4

  def listNotebooks(): IO[List[String]] =
    IO(Files.walk(path, maxDepth, FileVisitOption.FOLLOW_LINKS).iterator().asScala.drop(1).filter(validNotebook).toList).map {
      paths => paths.map {
        path => this.path.relativize(path).toString
      }
    }

  def notebookExists(path: String): IO[Boolean] = {
    val repoPath = this.path.resolve(path)
    IO(repoPath.toFile.exists())
  }

  def relativeDepth(relativePath: String): Int = {

    val fullPath = pathOf(relativePath).iterator().asScala
    val nbPath = path.iterator().asScala

    fullPath.dropWhile(elem => nbPath.contains(elem)).length
  }

  def emptyNotebook(path: String, title: String): Notebook = Notebook(
    ShortString(path),
    ShortList(
      NotebookCell(0, "text", s"# $title\n\nThis is a text cell. Start editing!") :: Nil
    ),
    Some(NotebookConfig(Option(config.dependencies.asInstanceOf[DependencyConfigs]), Option(config.exclusions.map(TinyString.apply)), Option(config.repositories), Option(config.spark)))
  )

  def createNotebook(relativePath: String, maybeUriOrContent: OptionEither[String, String] = OptionEither.Neither): IO[String] = {
    val ext = s".$defaultExtension"
    val noExtPath = relativePath.replaceFirst("""^/+""", "").stripSuffix(ext)
    val extPath = noExtPath + ext

    if (relativeDepth(relativePath) > maxDepth) {
      IO.raiseError(new IllegalArgumentException(s"Input path ($relativePath) too deep, maxDepth is $maxDepth"))
    } else {
      notebookExists(extPath).flatMap {
        case true  => IO.raiseError(new FileAlreadyExistsException(extPath))
        case false =>
          maybeUriOrContent.fold(
            uri => {
              BlazeClientBuilder[IO](executionContext).resource.use { client =>
                client.expect[String](uri)
              }.flatMap { content =>
                writeString(extPath, content)
              }
            },
            content => {
              if (relativePath.endsWith(".json")) { // assume zeppelin
                import io.circe.syntax._
                import io.circe.parser.parse
                for {
                  parsed <- IO.fromEither(parse(content))
                  zep <- IO.fromEither(parsed.as[ZeppelinNotebook])
                  jup = zep.toJupyterNotebook
                  jupStr = Printer.spaces2.copy(dropNullValues = true).pretty(jup.asJson)
                  io <- writeString(extPath, jupStr)
                } yield io
              } else {
                writeString(extPath, content)
              }
            },
            {
              val defaultTitle = noExtPath.split('/').last.replaceAll("[\\s\\-_]+", " ").trim()
              saveNotebook(extPath, emptyNotebook(extPath, defaultTitle))
            }
          ) map (_ => extPath)
      }
    }
  }
}
