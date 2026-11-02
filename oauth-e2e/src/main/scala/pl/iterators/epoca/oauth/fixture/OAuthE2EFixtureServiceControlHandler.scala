package pl.iterators.epoca.oauth.fixture

import cats.effect.IO
import io.circe.Json
import org.apache.pekko.http.scaladsl.model.StatusCodes
import pl.iterators.epoca.oauth.fixture.OAuthE2EFixtureControlProtocol.ControlCommand.{Advance300Seconds, Cleanup, RevokeOAuth029, Setup, Status}
import pl.iterators.epoca.oauth.fixture.OAuthE2EFixtureControlProtocol.{ControlCommand, ControlRequestContext, ControlResult}
import pl.iterators.epoca.oauth.fixture.OAuthE2EFixtureDomain.{AdvanceResult, FixtureStatusResult}

import java.util.UUID

final class OAuthE2EFixtureServiceControlHandler(service: OAuthE2EFixtureService) extends OAuthE2EFixtureControlHandler {
  override def handle(
    command: ControlCommand,
    idempotencyKey: UUID,
    context: ControlRequestContext
  ): IO[ControlResult] =
    command match {
      case Setup(runId)             => service.setup(runId, idempotencyKey, context).flatMap(setupResult)
      case Advance300Seconds(runId) => service.advance300(runId, idempotencyKey, context).map(advanceResult)
      case RevokeOAuth029(runId)    => service.revokeOAuth029(runId, idempotencyKey, context).map(revokeResult)
      case Cleanup(runId)           => service.cleanup(runId, idempotencyKey, context).map(cleanupResult)
      case Status(runId)            => service.status(runId, idempotencyKey, context).map(statusResult)
    }

  private def setupResult(value: OAuthE2EFixtureSetupResult): IO[ControlResult] = value match {
    case OAuthE2EFixtureSetupResult.Applied(credentials) =>
      credentials.releaseForControlResponse().map { released =>
        response(
          StatusCodes.OK,
          "applied",
          "credentials" -> Json.fromValues(released.map { case (alias, credential) =>
            Json.obj(
              "alias"        -> Json.fromString(alias.databaseValue),
              "clientId"     -> Json.fromString(credential.clientId.toString),
              "clientSecret" -> Json.fromString(credential.clientSecret.toString)
            )
          })
        )
      }
    case OAuthE2EFixtureSetupResult.CredentialsUnavailable => IO.pure(response(StatusCodes.Gone, "credentials-unavailable"))
    case OAuthE2EFixtureSetupResult.Conflict               => IO.pure(response(StatusCodes.Conflict, "conflict"))
    case OAuthE2EFixtureSetupResult.CommandBudgetExhausted => IO.pure(response(StatusCodes.TooManyRequests, "command-budget-exhausted"))
    case OAuthE2EFixtureSetupResult.TimedOut               => IO.pure(failed)
  }

  private def advanceResult(value: AdvanceResult): ControlResult = value match {
    case AdvanceResult.Applied(before, after, rowVersion, count) =>
      response(
        StatusCodes.OK,
        "applied",
        "offsetBeforeSeconds"  -> Json.fromInt(before),
        "offsetAfterSeconds"   -> Json.fromInt(after),
        "rowVersion"           -> Json.fromLong(rowVersion),
        "acceptedCommandCount" -> Json.fromInt(count)
      )
    case AdvanceResult.Idempotent(offset, rowVersion, count) =>
      response(
        StatusCodes.OK,
        "idempotent",
        "offsetSeconds"        -> Json.fromInt(offset),
        "rowVersion"           -> Json.fromLong(rowVersion),
        "acceptedCommandCount" -> Json.fromInt(count)
      )
    case AdvanceResult.Conflict(offset, rowVersion, count) =>
      response(
        StatusCodes.Conflict,
        "conflict",
        "offsetSeconds"        -> Json.fromInt(offset),
        "rowVersion"           -> Json.fromLong(rowVersion),
        "acceptedCommandCount" -> Json.fromInt(count)
      )
    case AdvanceResult.CommandBudgetExhausted(offset, rowVersion, count) =>
      response(
        StatusCodes.TooManyRequests,
        "command-budget-exhausted",
        "offsetSeconds"        -> Json.fromInt(offset),
        "rowVersion"           -> Json.fromLong(rowVersion),
        "acceptedCommandCount" -> Json.fromInt(count)
      )
  }

  private def revokeResult(value: OAuthE2EFixtureRevokeResult): ControlResult = value match {
    case OAuthE2EFixtureRevokeResult.Applied                => response(StatusCodes.OK, "applied")
    case OAuthE2EFixtureRevokeResult.Idempotent             => response(StatusCodes.OK, "idempotent")
    case OAuthE2EFixtureRevokeResult.Conflict               => response(StatusCodes.Conflict, "conflict")
    case OAuthE2EFixtureRevokeResult.CommandBudgetExhausted => response(StatusCodes.TooManyRequests, "command-budget-exhausted")
    case OAuthE2EFixtureRevokeResult.TimedOut               => failed
  }

  private def cleanupResult(value: OAuthE2EFixtureCleanupResult): ControlResult = value match {
    case OAuthE2EFixtureCleanupResult.Cleaned        => response(StatusCodes.OK, "cleaned")
    case OAuthE2EFixtureCleanupResult.AlreadyCleaned => response(StatusCodes.OK, "already-cleaned")
    case OAuthE2EFixtureCleanupResult.Conflict       => response(StatusCodes.Conflict, "conflict")
    case OAuthE2EFixtureCleanupResult.TimedOut       => failed
  }

  private def statusResult(value: FixtureStatusResult): ControlResult = value match {
    case FixtureStatusResult.Snapshot(runState, completedSteps, totalSteps, acceptedCommandCount) =>
      response(
        StatusCodes.OK,
        "status",
        "runState"              -> Json.fromString(runState.databaseValue),
        "completedCleanupSteps" -> Json.fromInt(completedSteps),
        "totalCleanupSteps"     -> Json.fromInt(totalSteps),
        "acceptedCommandCount"  -> Json.fromInt(acceptedCommandCount)
      )
    case FixtureStatusResult.Conflict               => response(StatusCodes.Conflict, "conflict")
    case FixtureStatusResult.CommandBudgetExhausted => response(StatusCodes.TooManyRequests, "command-budget-exhausted")
  }

  private def response(
    status: org.apache.pekko.http.scaladsl.model.StatusCode,
    result: String,
    fields: (String, Json)*
  ): ControlResult =
    ControlResult(status, Json.obj(("result" -> Json.fromString(result)) +: fields: _*).noSpaces)

  private val failed = response(StatusCodes.InternalServerError, "fixture-control-failed")
}
