package co.edu.eafit.dis.pi2.cicd

import scala.concurrent.duration._

import cats.effect.IO
import cats.effect.Resource
import smithy4s.Timestamp
import weaver.IOSuite
import weaver.scalacheck.CheckConfig
import weaver.scalacheck.Checkers

import domain.model.NonEmptyString
import domain.model.Todo
import domain.model.TodoStatus
import service.TodoService

object TodoServiceAppSpec extends IOSuite with Checkers:
  override type Res = TodoService[IO]
  override def sharedResource: Resource[IO, TodoService[IO]] =
    resources.TodoServiceAppResource.make.flatMap { server =>
      client.todo.make(
        uri = server.baseUri
      )
    }

  // Ensure each test runs sequentially.
  override val maxParallelism = 1

  // Ensure each property case runs sequentially.
  override val checkConfig = CheckConfig.default.withPerPropertyParallelism(1)

  // Tests.
  test(
    name = "Inserting a TODO whose due date is after now should list it as PENDING"
  ) { client =>
    IO.realTimeInstant.flatMap { now =>
      forall(
        generators.addTodoData(
          dueDate = generators.dueDate.between(
            startTime = now,
            endTime = now.plusSeconds(10.days.toSeconds)
          )
        )
      ) { addTodoData =>
        for
          addTodoResponse <- client.addTodo(
            reminder = addTodoData.reminder,
            dueTime = addTodoData.dueTime
          )
          listTodosResponse <- client.listTodos()
          todos = listTodosResponse.todos
          expectedTodo = Todo(
            todoId = addTodoResponse.todoId,
            reminder = addTodoData.reminder,
            status = TodoStatus.Pending(
              dueTime = addTodoData.dueTime
            )
          )
        yield expect(todos.contains(expectedTodo))
      }
    }
  }

  test(
    name = "Inserting a TODO whose due date is before now should list it as OVERDUE"
  ) { client =>
    IO.realTimeInstant.flatMap { now =>
      forall(
        generators.addTodoData(
          dueDate = generators.dueDate.between(
            startTime = now.minusSeconds(10.days.toSeconds),
            endTime = now
          )
        )
      ) { addTodoData =>
        for
          addTodoResponse <- client.addTodo(
            reminder = addTodoData.reminder,
            dueTime = addTodoData.dueTime
          )
          listTodosResponse <- client.listTodos()
          todos = listTodosResponse.todos
          expectedTodo = Todo(
            todoId = addTodoResponse.todoId,
            reminder = addTodoData.reminder,
            status = TodoStatus.Overdue(
              dueTime = addTodoData.dueTime
            )
          )
        yield expect(todos.contains(expectedTodo))
      }
    }
  }

  test(
    name = "Completing a TODO should list it as COMPLETED"
  ) { client =>
    IO.realTimeInstant.flatMap { now =>
      forall(
        generators.addTodoData(
          dueDate = generators.dueDate.between(
            startTime = now.minusSeconds(10.days.toSeconds),
            endTime = now.plusSeconds(10.days.toSeconds)
          )
        )
      ) { addTodoData =>
        for
          addTodoResponse <- client.addTodo(
            reminder = addTodoData.reminder,
            dueTime = addTodoData.dueTime
          )
          _ <- client.completeTodo(
            todoId = addTodoResponse.todoId
          )
          listTodosResponse <- client.listTodos()
          todos = listTodosResponse.todos
        yield exists(todos) { todo =>
          expect.same(
            expected = addTodoResponse.todoId,
            found = todo.todoId
          ) && expect.same(
            expected = addTodoData.reminder,
            found = todo.reminder
          ) && matches(todo.status) { case TodoStatus.Completed(dueTime, completionTime) =>
            expect.same(
              expected = addTodoData.dueTime,
              found = dueTime
            ) && expect.all(
              completionTime.epochSecond <= (now.getEpochSecond + 10),
              completionTime.epochSecond >= (now.getEpochSecond - 10)
            )
          }
        }
      }
    }
  }

  test(
    name = "Editing a TODO should remove COMPLETED status"
  ) { client =>
    IO.realTimeInstant.flatMap { now =>
      forall(
        generators.addTodoData(
          dueDate = generators.dueDate.between(
            startTime = now,
            endTime = now.plusSeconds(10.days.toSeconds)
          )
        )
      ) { addTodoData =>
        for
          addTodoResponse <- client.addTodo(
            reminder = addTodoData.reminder,
            dueTime = addTodoData.dueTime
          )
          _ <- client.completeTodo(
            todoId = addTodoResponse.todoId
          )
          newReminder = NonEmptyString(addTodoData.reminder.value.reverse)
          _ <- client.editTodo(
            todoId = addTodoResponse.todoId,
            reminder = newReminder,
            dueTime = addTodoData.dueTime
          )
          listTodosResponse <- client.listTodos()
          todos = listTodosResponse.todos
          expectedTodo = Todo(
            todoId = addTodoResponse.todoId,
            reminder = newReminder,
            status = TodoStatus.Pending(
              dueTime = addTodoData.dueTime
            )
          )
        yield expect(todos.contains(expectedTodo))
      }
    }
  }
end TodoServiceAppSpec
