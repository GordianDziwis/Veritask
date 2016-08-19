package controllers

import java.net.URL
import java.util.UUID
import javax.inject.Inject

import config.ConfigBanana
import models._
import play.api.i18n.{I18nSupport, MessagesApi}
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{JsNull, JsString, Json}
import play.api.mvc.{Action, Controller}
import services._

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

class Tasks @Inject() (
                        val tasksetRepo: TasksetMongoRepo,
                        val taskRepo: TaskMongoRepo,
                        val linkRepo: LinkMongoRepo,
                        val userRepo: UserMongoRepo,
                        val evalDataRepo: EvalDataRepo,
                        val messagesApi: MessagesApi,
                        val configuration: play.api.Configuration)
  extends Controller
  with I18nSupport
  with ConfigBanana {

  def requestTaskEval(
                       name: String,
                       taskset: Option[String],
                       ability: Boolean) = Action.async { implicit request =>
    for {
      user <- getUser(name)
      evalData <- getEvalData(user)
      widgetData <- getWidgetData(name, taskset)
    } yield Ok(Json.toJson(widgetData))
  }

  def getEvalData(user: User): Future[EvalData] = {
    evalDataRepo.search("user_id", user._id.toString) flatMap {
      case eD: Traversable[EvalData] if eD.nonEmpty =>
        val evalData = eD.head
        val stampedEvalData = evalData.copy(
          timeStamps = System.currentTimeMillis() :: evalData.timeStamps)
        evalDataRepo.save(stampedEvalData)
      case _ =>
        evalDataRepo.save(
          EvalData(
            UUID.randomUUID(),
            user._id,
            selectGroup,
            List(System.currentTimeMillis())
          )
        )
    }
  }

  def selectGroup: Int = {
    5
  }

  def getWidgetData(name: String, taskset: Option[String]): Future[Widget] = {
    for {
      user <- getUser(name)
      task <- taskRepo.selectTaskToVerify(taskset, user.validations.map(_.task_id))
      taskset <- tasksetRepo.findById(task.taskset)
      link <- linkRepo.findById(task.link_id)
      task <- updateTaskAttributes(task, taskset.get, link.get)
    } yield Widget(user._id, link.get, task, taskset.get.template)
  }

  def requestTask(name: String, taskset: Option[String]) = Action.async {
    implicit request => getWidgetData(name, taskset).map(data => Ok(Json.toJson(data)))
  }

  def getUser(name: String): Future[User] = {
    userRepo.search("name", name) flatMap {
      case users: Traversable[User] if users.nonEmpty => Future(users.head)
      case _ => userRepo.save(User(UUID.randomUUID(), name))
    }
  }

  def isTurn(user: User, evalData: EvalData, ability:Boolean): Boolean = {
    val timeSinceLastRequest = System.currentTimeMillis() - evalData.timeStamps.head
    val groups = configuration.getLongSeq("veritask.groups").get
    timeSinceLastRequest > groups(evalData.group) || user.name == "testUser"
  }

  def updateTaskAttributes(
    task: Task, taskset: Taskset, link: Link): Future[Task] = {
      val subQueryString = taskset.subjectAttributesQuery.map(_.replaceAll(
        "\\{\\{\\s*linkSubjectURI\\s*\\}\\}", "<" + link.linkSubject + ">"
      ))
      val objQueryString = taskset.objectAttributesQuery.map(_.replaceAll(
        "\\{\\{\\s*linkObjectURI\\s*\\}\\}", "<" + link.linkObject + ">"
      ))
      for {
        subAttributes <- queryAttribute(
          taskset.subjectEndpoint,
          subQueryString,
          task.subjectAttributes)
        objAttributes <- queryAttribute(
          taskset.objectEndpoint,
          objQueryString,
          task.objectAttributes)
        updatedTask = task.copy(
          subjectAttributes = subAttributes,
          objectAttributes = objAttributes)
        savedTask <- taskRepo.save(updatedTask)
      } yield savedTask
  }

  def queryAttribute(
    endpointOpt: Option[String],
    queryStringOpt: Option[String],
    attribute: Option[Map[String, String]]): Future[Option[Map[String, String]]] = {

    import ops._
    import sparqlHttp.sparqlEngineSyntax._
    import sparqlOps._

    val result = (endpointOpt, queryStringOpt, attribute) match {
      case (Some(endpoint), Some(queryString), None) =>
        val endpointURL = new URL(endpoint)
        for {
          query <- parseSelect(queryString)
          solutions <- endpointURL.executeSelect(query, Map())
          solution = solutions.iterator.next
        } yield {
          Some(solution.vars.map(a => a -> solution.get(a).toString).toMap)
        }
      case _ => Try(None)
    }
    result match {
      case Success(attributes) => Future.successful(attributes)
      case Failure(t) => Future.failed(t)
    }
  }
}
