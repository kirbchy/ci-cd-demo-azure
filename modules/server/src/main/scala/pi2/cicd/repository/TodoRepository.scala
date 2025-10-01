package co.edu.eafit.dis.pi2.cicd
package repository

import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

import cats.effect.IO
import skunk.Codec
import skunk.Session
import skunk.codec.all._
import skunk.data.Completion
import skunk.syntax.all.sql

import model.TodoData

trait TodoRepository:
  def saveTodo(todo: TodoData): IO[Unit]
  def editTodo(todo: TodoData): IO[Boolean]
  def markTodoAsCompleted(todoId: UUID, completionTime: Instant): IO[Boolean]
  def listTodos: IO[List[TodoData]]

object TodoRepository:
  private val instant: Codec[Instant] =
    timestamptz.imap(_.toInstant)(_.atOffset(ZoneOffset.UTC))

  private val todoData: Codec[TodoData] =
    (uuid *: text *: instant *: instant.opt).to[TodoData]

  private def ensureOnlyOneUpdatedRow(command: IO[Completion]): IO[Boolean] =
    command.flatMap {
      case Completion.Update(1) =>
        IO.pure(true)

      case Completion.Update(0) =>
        IO.pure(false)

      case completion =>
        IO.raiseError(IllegalStateException(s"Unexpected SQL completion: ${completion}"))
    }

  def make(
    session: Session[IO]
  ): TodoRepository =
    new TodoRepository:
      override def saveTodo(todo: TodoData): IO[Unit] =
        session
          .execute(
            command = sql"INSERT INTO todos VALUES ${todoData.values}".command
          )(
            args = todo
          )
          .void

      override def editTodo(todo: TodoData): IO[Boolean] =
        ensureOnlyOneUpdatedRow:
          session.execute(
            command = sql"""UPDATE
                              todos
                            SET
                              reminder = ${text},
                              due_time = ${instant},
                              completion_time = NULL
                            WHERE
                              todo_id = ${uuid}
                        """.command
          )(
            args = (todo.reminder, todo.dueTime, todo.todoId)
          )

      override def markTodoAsCompleted(
        todoId: UUID,
        completionTime: Instant
      ): IO[Boolean] =
        ensureOnlyOneUpdatedRow:
          session.execute(
            command = sql"""UPDATE
                              todos
                            SET
                              completion_time = ${instant}
                            WHERE
                              todo_id = ${uuid}
                        """.command
          )(
            args = (completionTime, todoId)
          )

      override def listTodos: IO[List[TodoData]] =
        session.execute(
          query = sql"SELECT * FROM todos".query(todoData)
        )
  end make
end TodoRepository
