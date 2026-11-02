package pl.iterators.epoca.workunit.routers

import org.specs2.mutable.SpecificationLike
import pl.iterators.epoca.utils.ProjectDefinedPredefs
import pl.iterators.epoca.workunit.services.WorkUnitDtoService.WorkUnitDto
import pl.iterators.epoca.workunit.services.{UndoWorkUnitOverrideRequest, WorkUnitOverrideHistoryPage}
import pl.iterators.kebs.jsonschema.KebsJsonSchema

class WorkUnitOverrideSchemaSpec extends SpecificationLike with ProjectDefinedPredefs with KebsJsonSchema {
  "Work-unit response schema" should {
    "derive all source, effective and override fields" in {
      val schema = _root_.json.Json.schema[WorkUnitDto]
      schema.toString must contain("effectiveTaskId")
      schema.toString must contain("override")
      schema.toString must contain("candidateEventId")
    }
    "derive the undo request and additive history metadata" in {
      _root_.json.Json.schema[UndoWorkUnitOverrideRequest].toString must contain("targetEventId")
      val history = _root_.json.Json.schema[WorkUnitOverrideHistoryPage].toString
      history must contain("parentCorrectionId")
      history must contain("revertsEventId")
      history must contain("revertedBy")
    }
  }
}
