import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class ContractNormalizer {
  private static final Pattern RFC3339_DATE_TIME =
      Pattern.compile(
          "^\\d{4}-\\d{2}-\\d{2}[Tt]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:[Zz]|[+-]\\d{2}:\\d{2})$");
  private static final String BUILD_INFO_ROUTE = "/build-info";
  private static final String BUILD_INFO_RESPONSE_SCALA_TYPE =
      "pl.iterators.epoca.healthcheck.routers.HealthCheckRouter.BuildInfo";
  private static final String METHOD_LABEL = "METHOD";
  private static final String PIVOT_REQUEST_ROUTE =
      "/organizations/{organizationSlug}/pivot-tables/work-units/query";
  private static final String PIVOT_REQUEST_SCALA_TYPE =
      "pl.iterators.epoca.stats.domain.WorkUnitPivotQuery.Request";
  private static final String REQUEST_SCALA_TYPE_LABEL = "REQUEST SCALA TYPE";
  private static final String PIVOT_RESPONSE_SCALA_TYPE =
      "pl.iterators.epoca.stats.domain.WorkUnitPivotQuery.Response";
  private static final String RESPONSE_SCALA_TYPE_LABEL = "RESPONSE SCALA TYPE";
  private static final List<String> SAMPLE_LABELS =
      List.of(
          "REQUEST MINIMAL JSON",
          "REQUEST MAXIMAL JSON",
          "RESPONSE MINIMAL JSON",
          "RESPONSE MAXIMAL JSON");
  private static final List<String> SCHEMA_LABELS =
      List.of("REQUEST SCHEMA", "RESPONSE SCHEMA");
  private static final String ROUTE_LABEL = "ROUTE";
  private static final String ROUTE_ALL_LABEL = "ROUTE WITH ALL PARAMS";
  private static final Set<String> PIVOT_FIELD_VALUE_BRANCHES =
      Set.of(
          "{\"type\":\"integer\"}",
          "{\"enum\":[\"Null\"],\"type\":\"string\"}",
          "{\"type\":\"string\"}",
          "{\"format\":\"date\",\"type\":\"string\"}",
          "{\"additionalProperties\":false,\"properties\":{\"value\":{\"enum\":[\"Note\",\"Worklog\"],\"type\":\"string\"}},\"required\":[\"value\"],\"type\":\"object\"}",
          "{\"pattern\":\"^[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}$\",\"type\":\"string\"}");
  private static final Set<String> SCHEMA_KEYWORDS =
      Set.of(
          "$id",
          "$schema",
          "additionalProperties",
          "allOf",
          "anyOf",
          "const",
          "default",
          "description",
          "enum",
          "examples",
          "exclusiveMaximum",
          "exclusiveMinimum",
          "format",
          "items",
          "maxItems",
          "maxLength",
          "maximum",
          "minItems",
          "minLength",
          "minimum",
          "oneOf",
          "pattern",
          "properties",
          "required",
          "title",
          "type",
          "uniqueItems");

  private ContractNormalizer() {}

  public static void main(String[] args) {
    try {
      if (args.length == 2 && args[0].equals("validate-openapi-diff")) {
        validateOpenApiDiff(parseFile(Path.of(args[1])));
      } else if (args.length == 3 && args[0].equals("normalize-simpledocs")) {
        normalizeSimpleDocs(Path.of(args[1]), Path.of(args[2]));
      } else {
        fail("Usage: ContractNormalizer <validate-openapi-diff file|normalize-simpledocs input output>");
      }
    } catch (ContractFailure failure) {
      System.err.println("Error: " + failure.getMessage());
      System.exit(73);
    } catch (IOException failure) {
      System.err.println("Error: Contract normalization I/O failed: " + failure.getMessage());
      System.exit(74);
    }
  }

  private static Object parseFile(Path path) throws IOException {
    return new JsonParser(Files.readString(path, StandardCharsets.UTF_8)).parse();
  }

  private static void validateOpenApiDiff(Object value) {
    if (!(value instanceof Map<?, ?>)) fail("OpenAPI diff is not a JSON object");
    Map<?, ?> root = (Map<?, ?>) value;
    if (root.isEmpty()) return;
    List<Object> path = new ArrayList<>();
    validateOpenApiNode(value, path);
  }

  private static void validateOpenApiNode(Object value, List<Object> path) {
    if (value instanceof Map<?, ?> map) {
      if (map.isEmpty()) {
        if (!isAllowedOpenApiLeaf(path)) fail("OpenAPI diff contains unsupported changes");
        return;
      }
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (!(entry.getKey() instanceof String)) fail("OpenAPI diff contains a non-string key");
        String key = (String) entry.getKey();
        path.add(key);
        validateOpenApiNode(entry.getValue(), path);
        path.remove(path.size() - 1);
      }
      return;
    }
    if (value instanceof List<?> list) {
      if (list.isEmpty()) {
        if (!isAllowedOpenApiLeaf(path)) fail("OpenAPI diff contains unsupported changes");
        return;
      }
      for (int index = 0; index < list.size(); index++) {
        path.add(index);
        validateOpenApiNode(list.get(index), path);
        path.remove(path.size() - 1);
      }
      return;
    }
    if (!isAllowedOpenApiLeaf(path)) fail("OpenAPI diff contains unsupported changes");
  }

  private static boolean isAllowedOpenApiLeaf(List<Object> path) {
    if (path.size() < 13
        || !at(path, 0, "paths")
        || !at(path, 1, "modified")
        || !(path.get(2) instanceof String)
        || !at(path, 3, "operations")
        || !at(path, 4, "modified")
        || !(path.get(5) instanceof String)) return false;

    int index = 6;
    if (at(path, index, "requestBody")) {
      index += 1;
    } else if (at(path, index, "responses")
        && at(path, index + 1, "modified")
        && path.get(index + 2) instanceof String) {
      index += 3;
    } else if (at(path, index, "parameters")
        && at(path, index + 1, "modified")
        && path.get(index + 2) instanceof String
        && path.get(index + 3) instanceof String
        && at(path, index + 4, "schema")
        && at(path, index + 5, "example")
        && isFromOrTo(path, index + 6)
        && path.size() == index + 7) {
      return true;
    } else {
      return false;
    }

    return at(path, index, "content")
        && at(path, index + 1, "modified")
        && path.get(index + 2) instanceof String
        && at(path, index + 3, "examples")
        && at(path, index + 4, "modified")
        && isGeneratedExampleName(path, index + 5)
        && at(path, index + 6, "value")
        && isFromOrTo(path, index + 7)
        && path.size() == index + 8;
  }

  private static boolean at(List<Object> path, int index, String expected) {
    return index >= 0 && index < path.size() && expected.equals(path.get(index));
  }

  private static boolean isFromOrTo(List<Object> path, int index) {
    return at(path, index, "from") || at(path, index, "to");
  }

  private static boolean isGeneratedExampleName(List<Object> path, int index) {
    return at(path, index, "minimal") || at(path, index, "maximal");
  }

  private static void normalizeSimpleDocs(Path input, Path output) throws IOException {
    String html = normalizeLines(Files.readString(input, StandardCharsets.UTF_8));
    Map<String, Block> blocks = new LinkedHashMap<>();
    for (String label : SAMPLE_LABELS) findUniqueBlock(html, label, true, blocks);
    for (String label : SCHEMA_LABELS) findUniqueBlock(html, label, true, blocks);
    findUniqueBlock(html, REQUEST_SCALA_TYPE_LABEL, true, blocks);
    findUniqueBlock(html, RESPONSE_SCALA_TYPE_LABEL, true, blocks);
    findUniqueBlock(html, METHOD_LABEL, false, blocks);
    findUniqueBlock(html, ROUTE_LABEL, false, blocks);
    findUniqueBlock(html, ROUTE_ALL_LABEL, false, blocks);

    validatePairs(blocks, "REQUEST");
    validatePairs(blocks, "RESPONSE");

    List<Replacement> replacements = new ArrayList<>();
    for (String label : SCHEMA_LABELS) {
      Block block = blocks.get(label);
      if (block != null) {
        Object schema = new JsonParser(block.content).parse();
        inspectSchema(schema, label);
        replacements.add(new Replacement(block.contentStart, block.contentEnd, canonicalSchema(schema)));
      }
    }
    for (String label : SAMPLE_LABELS) {
      Block block = blocks.get(label);
      if (block != null) {
        replacements.add(
            new Replacement(
                block.contentStart, block.contentEnd, normalizeSample(label, block, blocks)));
      }
    }

    Block route = blocks.get(ROUTE_LABEL);
    Block routeAll = blocks.get(ROUTE_ALL_LABEL);
    if (routeAll != null) {
      if (route == null) fail("SimpleDocs route with all params has no ROUTE template");
      replacements.add(
          new Replacement(
              routeAll.contentStart,
              routeAll.contentEnd,
              normalizeRoute(route.content, routeAll.content)));
    }

    replacements.sort((left, right) -> Integer.compare(right.start, left.start));
    StringBuilder normalized = new StringBuilder(html);
    for (Replacement replacement : replacements) {
      normalized.replace(replacement.start, replacement.end, replacement.value);
    }
    Files.createDirectories(output.getParent());
    Files.writeString(output, normalized.toString(), StandardCharsets.UTF_8);
  }

  private static String normalizeLines(String input) {
    String unix = input.replace("\r\n", "\n").replace('\r', '\n');
    String[] lines = unix.split("\n", -1);
    StringBuilder result = new StringBuilder(unix.length());
    for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
      String line = lines[lineIndex].replaceFirst("[\\t ]+$", "");
      result.append(line);
      if (lineIndex + 1 < lines.length) result.append('\n');
    }
    return result.toString();
  }

  private static void findUniqueBlock(
      String html, String label, boolean preformatted, Map<String, Block> blocks) {
    String rowOpening = "<tr><td><b>" + label + "</b></td><td>";
    int rowOpen = html.indexOf(rowOpening);
    if (rowOpen < 0) return;
    if (html.indexOf(rowOpening, rowOpen + rowOpening.length()) >= 0)
      fail("Duplicate targeted SimpleDocs block: " + label);
    String opening = rowOpening + (preformatted ? "<pre>" : "");
    String closing = (preformatted ? "</pre>" : "") + "</td></tr>";
    int open = html.indexOf(opening);
    if (open != rowOpen) fail("Malformed targeted SimpleDocs block: " + label);
    int contentStart = open + opening.length();
    int close = html.indexOf(closing, contentStart);
    int nextTarget = html.indexOf("<tr><td><b>", contentStart);
    if (close < 0 || (nextTarget >= 0 && nextTarget < close))
      fail("Unterminated targeted SimpleDocs block: " + label);
    blocks.put(label, new Block(contentStart, close, html.substring(contentStart, close)));
  }

  private static void validatePairs(Map<String, Block> blocks, String side) {
    Block schemaBlock = blocks.get(side + " SCHEMA");
    Block minimal = blocks.get(side + " MINIMAL JSON");
    Block maximal = blocks.get(side + " MAXIMAL JSON");
    int present = (schemaBlock == null ? 0 : 1) + (minimal == null ? 0 : 1) + (maximal == null ? 0 : 1);
    if (present == 0) return;
    if (present != 3) fail("Unpaired targeted SimpleDocs blocks: " + side);
    Object schema = new JsonParser(schemaBlock.content).parse();
    inspectSchema(schema, side + " SCHEMA");
    Block route = blocks.get(ROUTE_LABEL);
    Block method = blocks.get(METHOD_LABEL);
    Block requestScalaType = blocks.get(REQUEST_SCALA_TYPE_LABEL);
    // OpenApiContractPostProcessor protects the exact /build-info object schema and examples in
    // OpenAPI, while Baklava 0.3.1 writes an incorrect root-string schema only in SimpleDocs.
    boolean buildInfoSchemaCompatibility =
        isBuildInfoSchemaCompatibility(
            side, schema, route, method, blocks.get(RESPONSE_SCALA_TYPE_LABEL));
    boolean pivotResponseSchemaCompatibility =
        isPivotResponseSchemaCompatibility(
            side, schema, route, method, blocks.get(RESPONSE_SCALA_TYPE_LABEL));
    Object pivotFieldValueSchema =
        pivotFieldValueCompatibilitySchema(
            side, schema, route, method, requestScalaType);
    if (minimal != null)
      validateSampleBlock(
          minimal,
          schema,
          side + " MINIMAL JSON",
          (buildInfoSchemaCompatibility && isExactBuildInfoSample(minimal))
              || pivotResponseSchemaCompatibility,
          side.equals("REQUEST") ? requestScalaType : null,
          pivotFieldValueSchema);
    if (maximal != null)
      validateSampleBlock(
          maximal,
          schema,
          side + " MAXIMAL JSON",
          (buildInfoSchemaCompatibility && isExactBuildInfoSample(maximal))
              || pivotResponseSchemaCompatibility,
          side.equals("REQUEST") ? requestScalaType : null,
          pivotFieldValueSchema);
  }

  private static void validateSampleBlock(
      Block block,
      Object schema,
      String label,
      boolean skipSchemaValidation,
      Block requestScalaType,
      Object pivotFieldValueSchema) {
    Object sample = new JsonParser(block.content).parse();
    if (!skipSchemaValidation
        && !matches(schema, sample, pivotFieldValueSchema)
        && !matchesSingleFieldRequestProduct(schema, sample, requestScalaType))
      fail("SimpleDocs sample does not satisfy its schema: " + label);
  }

  private static Object pivotFieldValueCompatibilitySchema(
      String side,
      Object schema,
      Block route,
      Block method,
      Block requestScalaType) {
    if (!side.equals("REQUEST")
        || route == null
        || !route.content.equals(PIVOT_REQUEST_ROUTE)
        || method == null
        || !method.content.equals("POST")
        || requestScalaType == null
        || !requestScalaType.content.equals(PIVOT_REQUEST_SCALA_TYPE)) return null;

    Object filters = schemaProperty(schema, "filters");
    Object filterItem = schemaChild(filters, "items");
    Object includedValues = schemaProperty(filterItem, "includedValues");
    Object fieldValue = schemaChild(includedValues, "items");
    return isExactPivotFieldValueFingerprint(fieldValue) ? fieldValue : null;
  }

  private static boolean isBuildInfoSchemaCompatibility(
      String side,
      Object schemaObject,
      Block route,
      Block method,
      Block responseScalaType) {
    if (!side.equals("RESPONSE")
        || route == null
        || !route.content.equals(BUILD_INFO_ROUTE)
        || method == null
        || !method.content.equals("GET")
        || responseScalaType == null
        || !responseScalaType.content.equals(BUILD_INFO_RESPONSE_SCALA_TYPE)
        || !(schemaObject instanceof Map<?, ?> schema)
        || schema.size() != 3) return false;
    return "http://json-schema.org/draft-07/schema#".equals(schema.get("$schema"))
        && "".equals(schema.get("$id"))
        && "string".equals(schema.get("type"));
  }

  private static boolean isExactBuildInfoSample(Block block) {
    Object sample = new JsonParser(block.content).parse();
    if (!(sample instanceof Map<?, ?> object) || object.size() != 1) return false;
    return object.get("commitSha") instanceof String;
  }

  private static boolean isPivotResponseSchemaCompatibility(
      String side,
      Object schema,
      Block route,
      Block method,
      Block responseScalaType) {
    if (!side.equals("RESPONSE")
        || route == null
        || !route.content.equals(PIVOT_REQUEST_ROUTE)
        || method == null
        || !method.content.equals("POST")
        || responseScalaType == null
        || !responseScalaType.content.equals(PIVOT_RESPONSE_SCALA_TYPE)) return false;

    Object cell = schemaChild(schemaProperty(schema, "cells"), "items");
    Object row = schemaProperty(cell, "row");
    Object column = schemaProperty(cell, "column");
    Object rowValue =
        schemaProperty(schemaChild(schemaProperty(row, "path"), "items"), "value");
    Object columnValue =
        schemaProperty(schemaChild(schemaProperty(column, "path"), "items"), "value");
    Object filterMember = schemaChild(schemaProperty(schema, "filterMembers"), "items");
    Object member = schemaChild(schemaProperty(filterMember, "members"), "items");
    Object memberValue = schemaProperty(member, "value");
    return isExactPivotFieldValueFingerprint(rowValue)
        && isExactPivotFieldValueFingerprint(columnValue)
        && isExactPivotFieldValueFingerprint(memberValue);
  }

  private static Object schemaProperty(Object schema, String name) {
    Object properties = schemaChild(schema, "properties");
    return properties instanceof Map<?, ?> map ? map.get(name) : null;
  }

  private static Object schemaChild(Object schema, String name) {
    return schema instanceof Map<?, ?> map ? map.get(name) : null;
  }

  private static boolean isExactPivotFieldValueFingerprint(Object schemaObject) {
    if (!(schemaObject instanceof Map<?, ?> schema)
        || schema.size() != 1
        || !(schema.get("oneOf") instanceof List<?> branches)
        || branches.size() != 6) return false;
    Set<String> actual = new HashSet<>();
    for (Object branch : branches) actual.add(canonicalSchema(branch));
    return actual.size() == 6 && actual.equals(PIVOT_FIELD_VALUE_BRANCHES);
  }

  private static String normalizeSample(String label, Block block, Map<String, Block> blocks) {
    Object sample = new JsonParser(block.content).parse();
    if (label.startsWith("REQUEST ")) {
      Block schemaBlock = blocks.get("REQUEST SCHEMA");
      Object schema = schemaBlock == null ? null : new JsonParser(schemaBlock.content).parse();
      if (matchesSingleFieldRequestProduct(schema, sample, blocks.get(REQUEST_SCALA_TYPE_LABEL))) {
        Map<?, ?> product = (Map<?, ?>) sample;
        String key = (String) product.keySet().iterator().next();
        return "{" + quote(key) + ":\"__EPOCA_VERIFY_VALUE__\"}";
      }
    }
    if (label.startsWith("RESPONSE ")) {
      Block schemaBlock = blocks.get("RESPONSE SCHEMA");
      Object schema = schemaBlock == null ? null : new JsonParser(schemaBlock.content).parse();
      if (isBuildInfoSchemaCompatibility(
              "RESPONSE",
              schema,
              blocks.get(ROUTE_LABEL),
              blocks.get(METHOD_LABEL),
              blocks.get(RESPONSE_SCALA_TYPE_LABEL))
          && isExactBuildInfoSample(block)) return "{\"commitSha\":\"__EPOCA_VERIFY_VALUE__\"}";
      if (isPivotResponseSchemaCompatibility(
          "RESPONSE",
          schema,
          blocks.get(ROUTE_LABEL),
          blocks.get(METHOD_LABEL),
          blocks.get(RESPONSE_SCALA_TYPE_LABEL))) return canonicalJson(sample);
    }
    return "__EPOCA_VERIFY_" + label.replace(' ', '_') + "__";
  }

  private static boolean matchesSingleFieldRequestProduct(
      Object schema, Object sample, Block requestScalaType) {
    if (requestScalaType == null || requestScalaType.content.isBlank() || !isScalarSchema(schema))
      return false;
    if (!(sample instanceof Map<?, ?> product) || product.size() != 1) return false;
    Map.Entry<?, ?> entry = product.entrySet().iterator().next();
    return entry.getKey() instanceof String && matches(schema, entry.getValue());
  }

  private static boolean isScalarSchema(Object schemaObject) {
    if (!(schemaObject instanceof Map<?, ?> schema)) return false;
    Object type = schema.get("type");
    if (type instanceof String declared) return isScalarType(declared);
    if (type instanceof List<?> declaredTypes && !declaredTypes.isEmpty())
      return declaredTypes.stream().allMatch(item -> item instanceof String declared && isScalarType(declared));
    return false;
  }

  private static boolean isScalarType(String type) {
    return Set.of("boolean", "integer", "null", "number", "string").contains(type);
  }

  private static void inspectSchema(Object value, String context) {
    if (!(value instanceof Map<?, ?>)) fail("SimpleDocs schema is not an object: " + context);
    Map<?, ?> schema = (Map<?, ?>) value;
    for (Object keyObject : schema.keySet()) {
      if (!(keyObject instanceof String key) || !SCHEMA_KEYWORDS.contains(key))
        fail("Unsupported SimpleDocs schema keyword: " + keyObject);
    }
    schemaList(schema, "oneOf").forEach(item -> inspectSchema(item, context));
    schemaList(schema, "anyOf").forEach(item -> inspectSchema(item, context));
    schemaList(schema, "allOf").forEach(item -> inspectSchema(item, context));
    Object properties = schema.get("properties");
    if (properties != null) {
      if (!(properties instanceof Map<?, ?>)) fail("Schema properties must be an object");
      Map<?, ?> propertyMap = (Map<?, ?>) properties;
      propertyMap.values().forEach(item -> inspectSchema(item, context));
    }
    Object additional = schema.get("additionalProperties");
    if (additional != null && !(additional instanceof Boolean)) inspectSchema(additional, context);
    Object items = schema.get("items");
    if (items != null) inspectSchema(items, context);
    validateSchemaShape(schema);
  }

  private static void validateSchemaShape(Map<?, ?> schema) {
    Object type = schema.get("type");
    if (type != null) {
      if (type instanceof String stringType) {
        requireKnownType(stringType);
      } else if (type instanceof List<?> types && !types.isEmpty()) {
        for (Object item : types) {
          if (!(item instanceof String)) fail("Schema type union must contain strings");
          String stringType = (String) item;
          requireKnownType(stringType);
        }
      } else {
        fail("Schema type must be a string or a non-empty array");
      }
    }
    requireStringList(schema, "required");
    requireJsonArray(schema, "enum");
    requireBoolean(schema, "uniqueItems");
    for (String keyword : List.of("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum"))
      requireNumber(schema, keyword);
    for (String keyword : List.of("minLength", "maxLength", "minItems", "maxItems"))
      requireNonNegativeInteger(schema, keyword);
    if (schema.containsKey("pattern") && !(schema.get("pattern") instanceof String))
      fail("Schema pattern must be a string");
    if (schema.containsKey("format") && !(schema.get("format") instanceof String))
      fail("Schema format must be a string");
    Object additional = schema.get("additionalProperties");
    if (additional != null && !(additional instanceof Boolean) && !(additional instanceof Map<?, ?>))
      fail("Schema additionalProperties must be boolean or an object");
  }

  private static void requireKnownType(String type) {
    if (!Set.of("array", "boolean", "integer", "null", "number", "object", "string").contains(type))
      fail("Unsupported schema type: " + type);
  }

  private static void requireStringList(Map<?, ?> schema, String keyword) {
    if (!schema.containsKey(keyword)) return;
    Object value = schema.get(keyword);
    if (!(value instanceof List<?>)) fail("Schema " + keyword + " must be an array");
    List<?> list = (List<?>) value;
    for (Object item : list) if (!(item instanceof String)) fail("Schema " + keyword + " must contain strings");
  }

  private static void requireJsonArray(Map<?, ?> schema, String keyword) {
    if (schema.containsKey(keyword) && !(schema.get(keyword) instanceof List<?>))
      fail("Schema " + keyword + " must be an array");
  }

  private static void requireBoolean(Map<?, ?> schema, String keyword) {
    if (schema.containsKey(keyword) && !(schema.get(keyword) instanceof Boolean))
      fail("Schema " + keyword + " must be boolean");
  }

  private static void requireNumber(Map<?, ?> schema, String keyword) {
    if (schema.containsKey(keyword) && !(schema.get(keyword) instanceof BigDecimal))
      fail("Schema " + keyword + " must be a number");
  }

  private static void requireNonNegativeInteger(Map<?, ?> schema, String keyword) {
    if (!schema.containsKey(keyword)) return;
    Object value = schema.get(keyword);
    if (!(value instanceof BigDecimal number)
        || number.scale() > 0
        || number.signum() < 0
        || number.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0)
      fail("Schema " + keyword + " must be a non-negative integer");
  }

  private static boolean matches(Object schemaObject, Object value) {
    return matches(schemaObject, value, null);
  }

  private static boolean matches(
      Object schemaObject, Object value, Object pivotFieldValueSchema) {
    if (!(schemaObject instanceof Map<?, ?> schema)) return false;
    List<Object> allOf = schemaList(schema, "allOf");
    for (Object branch : allOf)
      if (!matches(branch, value, pivotFieldValueSchema)) return false;
    List<Object> anyOf = schemaList(schema, "anyOf");
    if (!anyOf.isEmpty()
        && anyOf.stream().noneMatch(branch -> matches(branch, value, pivotFieldValueSchema)))
      return false;
    List<Object> oneOf = schemaList(schema, "oneOf");
    if (!oneOf.isEmpty()) {
      long matches =
          oneOf.stream()
              .filter(
                  branch ->
                      matches(branch, value, pivotFieldValueSchema)
                          || (schemaObject == pivotFieldValueSchema
                              && value == null
                              && isBaklavaNullCaseObjectBranch(branch)))
              .count();
      if (schemaObject == pivotFieldValueSchema ? matches < 1 : matches != 1) return false;
    }
    if (schema.containsKey("const") && !jsonEquals(schema.get("const"), value)) return false;
    if (schema.containsKey("enum")
        && ((List<?>) schema.get("enum")).stream().noneMatch(item -> jsonEquals(item, value))) return false;
    if (!matchesType(schema.get("type"), value)) return false;

    if (value instanceof Map<?, ?> object
        && !matchesObject(schema, object, pivotFieldValueSchema)) return false;
    if (value instanceof List<?> array
        && !matchesArray(schema, array, pivotFieldValueSchema)) return false;
    if (value instanceof String string && !matchesString(schema, string)) return false;
    if (value instanceof BigDecimal number && !matchesNumber(schema, number)) return false;
    return true;
  }

  private static boolean isBaklavaNullCaseObjectBranch(Object branch) {
    return canonicalSchema(branch).equals("{\"enum\":[\"Null\"],\"type\":\"string\"}");
  }

  private static List<Object> schemaList(Map<?, ?> schema, String keyword) {
    Object value = schema.get(keyword);
    if (value == null) return List.of();
    if (!(value instanceof List<?>)) fail("Schema " + keyword + " must be non-empty");
    List<?> list = (List<?>) value;
    if (list.isEmpty()) fail("Schema " + keyword + " must be non-empty");
    return new ArrayList<>(list);
  }

  private static boolean matchesType(Object declared, Object value) {
    if (declared == null) return true;
    if (declared instanceof String type) return matchesSingleType(type, value);
    for (Object type : (List<?>) declared) if (matchesSingleType((String) type, value)) return true;
    return false;
  }

  private static boolean matchesSingleType(String type, Object value) {
    return switch (type) {
      case "array" -> value instanceof List<?>;
      case "boolean" -> value instanceof Boolean;
      case "integer" -> value instanceof BigDecimal number && number.stripTrailingZeros().scale() <= 0;
      case "null" -> value == null;
      case "number" -> value instanceof BigDecimal;
      case "object" -> value instanceof Map<?, ?>;
      case "string" -> value instanceof String;
      default -> false;
    };
  }

  private static boolean matchesObject(
      Map<?, ?> schema, Map<?, ?> value, Object pivotFieldValueSchema) {
    Object requiredObject = schema.get("required");
    List<?> required = requiredObject instanceof List<?> list ? list : List.of();
    if (!required.isEmpty()) {
      for (Object name : required) if (!value.containsKey(name)) return false;
    }
    Map<?, ?> properties =
        schema.get("properties") instanceof Map<?, ?> propertyMap ? propertyMap : Map.of();
    Object additional = schema.get("additionalProperties");
    for (Map.Entry<?, ?> entry : value.entrySet()) {
      Object propertySchema = properties.get(entry.getKey());
      if (propertySchema != null) {
        // Baklava encodes an absent Option as an explicit null in generated samples while its
        // adjacent Draft-07 schema leaves that property non-required instead of adding null to type.
        if (entry.getValue() == null && !required.contains(entry.getKey())) continue;
        if (!matches(propertySchema, entry.getValue(), pivotFieldValueSchema)) return false;
      } else if (Boolean.FALSE.equals(additional)) {
        return false;
      } else if (additional instanceof Map<?, ?>
          && !matches(additional, entry.getValue(), pivotFieldValueSchema)) {
        return false;
      }
    }
    return true;
  }

  private static boolean matchesArray(
      Map<?, ?> schema, List<?> value, Object pivotFieldValueSchema) {
    int minimum = integerKeyword(schema, "minItems", 0);
    int maximum = integerKeyword(schema, "maxItems", Integer.MAX_VALUE);
    if (value.size() < minimum || value.size() > maximum) return false;
    Object items = schema.get("items");
    if (items != null)
      for (Object item : value)
        if (!matches(items, item, pivotFieldValueSchema)) return false;
    if (Boolean.TRUE.equals(schema.get("uniqueItems"))) {
      Set<String> unique = new HashSet<>();
      for (Object item : value) if (!unique.add(canonicalJson(item))) return false;
    }
    return true;
  }

  private static int integerKeyword(Map<?, ?> schema, String keyword, int fallback) {
    Object value = schema.get(keyword);
    return value instanceof BigDecimal number ? number.intValueExact() : fallback;
  }

  private static boolean matchesString(Map<?, ?> schema, String value) {
    int length = value.codePointCount(0, value.length());
    if (length < integerKeyword(schema, "minLength", 0)
        || length > integerKeyword(schema, "maxLength", Integer.MAX_VALUE)) return false;
    Object patternObject = schema.get("pattern");
    if (patternObject instanceof String expression) {
      try {
        if (!Pattern.compile(expression).matcher(value).find()) return false;
      } catch (PatternSyntaxException failure) {
        fail("Unsupported schema pattern: " + expression);
      }
    }
    Object formatObject = schema.get("format");
    if (formatObject instanceof String format && !matchesFormat(format, value)) return false;
    return true;
  }

  private static boolean matchesFormat(String format, String value) {
    try {
      if (format.equals("date")) {
        LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE);
      } else if (format.equals("date-time")) {
        if (!RFC3339_DATE_TIME.matcher(value).matches()) return false;
        OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
      } else {
        fail("Unsupported schema format: " + format);
      }
      return true;
    } catch (DateTimeParseException failure) {
      return false;
    }
  }

  private static boolean matchesNumber(Map<?, ?> schema, BigDecimal value) {
    if (schema.get("minimum") instanceof BigDecimal minimum && value.compareTo(minimum) < 0) return false;
    if (schema.get("maximum") instanceof BigDecimal maximum && value.compareTo(maximum) > 0) return false;
    if (schema.get("exclusiveMinimum") instanceof BigDecimal minimum && value.compareTo(minimum) <= 0)
      return false;
    if (schema.get("exclusiveMaximum") instanceof BigDecimal maximum && value.compareTo(maximum) >= 0)
      return false;
    return true;
  }

  private static boolean jsonEquals(Object left, Object right) {
    if (left instanceof BigDecimal leftNumber && right instanceof BigDecimal rightNumber)
      return leftNumber.compareTo(rightNumber) == 0;
    return left == null ? right == null : left.equals(right);
  }

  private static String canonicalSchema(Object value) {
    if (value instanceof Map<?, ?> map) {
      List<String> keys = new ArrayList<>();
      for (Object key : map.keySet()) keys.add((String) key);
      Collections.sort(keys);
      StringBuilder result = new StringBuilder("{");
      for (int index = 0; index < keys.size(); index++) {
        if (index > 0) result.append(',');
        String key = keys.get(index);
        result.append(quote(key)).append(':');
        Object child = map.get(key);
        if ((key.equals("oneOf") || key.equals("anyOf")) && child instanceof List<?> list) {
          List<String> branches = new ArrayList<>();
          for (Object branch : list) branches.add(canonicalSchema(branch));
          Collections.sort(branches);
          result.append('[').append(String.join(",", branches)).append(']');
        } else {
          result.append(canonicalSchema(child));
        }
      }
      return result.append('}').toString();
    }
    if (value instanceof List<?> list) {
      List<String> items = new ArrayList<>();
      for (Object item : list) items.add(canonicalSchema(item));
      return "[" + String.join(",", items) + "]";
    }
    return canonicalJson(value);
  }

  private static String canonicalJson(Object value) {
    if (value == null) return "null";
    if (value instanceof String string) return quote(string);
    if (value instanceof Boolean bool) return bool.toString();
    if (value instanceof BigDecimal number) return number.stripTrailingZeros().toPlainString();
    if (value instanceof List<?> list) {
      List<String> items = new ArrayList<>();
      for (Object item : list) items.add(canonicalJson(item));
      return "[" + String.join(",", items) + "]";
    }
    if (value instanceof Map<?, ?> map) {
      List<String> keys = new ArrayList<>();
      for (Object key : map.keySet()) keys.add((String) key);
      Collections.sort(keys);
      List<String> entries = new ArrayList<>();
      for (String key : keys) entries.add(quote(key) + ":" + canonicalJson(map.get(key)));
      return "{" + String.join(",", entries) + "}";
    }
    fail("Unsupported JSON value");
    return "";
  }

  private static String quote(String value) {
    StringBuilder result = new StringBuilder("\"");
    for (int offset = 0; offset < value.length(); ) {
      int codePoint = value.codePointAt(offset);
      offset += Character.charCount(codePoint);
      switch (codePoint) {
        case '"' -> result.append("\\\"");
        case '\\' -> result.append("\\\\");
        case '\b' -> result.append("\\b");
        case '\f' -> result.append("\\f");
        case '\n' -> result.append("\\n");
        case '\r' -> result.append("\\r");
        case '\t' -> result.append("\\t");
        default -> {
          if (codePoint < 0x20) result.append(String.format("\\u%04x", codePoint));
          else result.appendCodePoint(codePoint);
        }
      }
    }
    return result.append('"').toString();
  }

  private static String normalizeRoute(String template, String route) {
    RouteParts templateParts = RouteParts.parse(template);
    RouteParts actualParts = RouteParts.parse(route);
    String[] templateSegments = templateParts.path.split("/", -1);
    String[] actualSegments = actualParts.path.split("/", -1);
    if (templateSegments.length != actualSegments.length)
      fail("SimpleDocs route does not match its template");
    List<String> path = new ArrayList<>();
    for (int index = 0; index < templateSegments.length; index++) {
      String expected = templateSegments[index];
      String actual = actualSegments[index];
      if (expected.matches("\\{[^{}]+}")) {
        if (actual.isEmpty() || actual.contains("/")) fail("SimpleDocs route does not match its template");
        path.add(expected);
      } else {
        if (!expected.equals(actual)) fail("SimpleDocs route does not match its template");
        path.add(actual);
      }
    }
    List<String> query = new ArrayList<>();
    for (String parameter : actualParts.query) {
      int equals = parameter.indexOf('=');
      String name = equals >= 0 ? parameter.substring(0, equals) : parameter;
      if (name.isEmpty()) fail("Malformed SimpleDocs route query");
      query.add(equals >= 0 ? name + "=__EPOCA_VERIFY_VALUE__" : name);
    }
    return String.join("/", path) + (query.isEmpty() ? "" : "?" + String.join("&", query));
  }

  private static void fail(String message) {
    throw new ContractFailure(message);
  }

  private record Block(int contentStart, int contentEnd, String content) {}

  private record Replacement(int start, int end, String value) {}

  private record RouteParts(String path, List<String> query) {
    private static RouteParts parse(String value) {
      if (value.contains("#")) fail("Malformed SimpleDocs route");
      int question = value.indexOf('?');
      if (question != value.lastIndexOf('?')) fail("Malformed SimpleDocs route");
      String path = question < 0 ? value : value.substring(0, question);
      String rawQuery = question < 0 ? "" : value.substring(question + 1);
      if (path.isEmpty() || (question >= 0 && rawQuery.isEmpty())) fail("Malformed SimpleDocs route");
      return new RouteParts(path, rawQuery.isEmpty() ? List.of() : List.of(rawQuery.split("&", -1)));
    }
  }

  private static final class ContractFailure extends RuntimeException {
    private ContractFailure(String message) {
      super(message);
    }
  }

  private static final class JsonParser {
    private final String input;
    private int offset;

    private JsonParser(String input) {
      this.input = input;
    }

    private Object parse() {
      Object result = value();
      whitespace();
      if (offset != input.length()) fail("Malformed JSON: trailing content");
      return result;
    }

    private Object value() {
      whitespace();
      if (offset >= input.length()) fail("Malformed JSON: unexpected end");
      return switch (input.charAt(offset)) {
        case '{' -> object();
        case '[' -> array();
        case '"' -> string();
        case 't' -> literal("true", Boolean.TRUE);
        case 'f' -> literal("false", Boolean.FALSE);
        case 'n' -> literal("null", null);
        default -> number();
      };
    }

    private Map<String, Object> object() {
      offset++;
      LinkedHashMap<String, Object> result = new LinkedHashMap<>();
      whitespace();
      if (consume('}')) return result;
      while (true) {
        whitespace();
        if (offset >= input.length() || input.charAt(offset) != '"') fail("Malformed JSON object key");
        String key = string();
        if (result.containsKey(key)) fail("Malformed JSON: duplicate key " + key);
        whitespace();
        require(':');
        result.put(key, value());
        whitespace();
        if (consume('}')) return result;
        require(',');
      }
    }

    private List<Object> array() {
      offset++;
      List<Object> result = new ArrayList<>();
      whitespace();
      if (consume(']')) return result;
      while (true) {
        result.add(value());
        whitespace();
        if (consume(']')) return result;
        require(',');
      }
    }

    private String string() {
      require('"');
      StringBuilder result = new StringBuilder();
      while (offset < input.length()) {
        char current = input.charAt(offset++);
        if (current == '"') return result.toString();
        if (current == '\\') {
          if (offset >= input.length()) fail("Malformed JSON string escape");
          char escaped = input.charAt(offset++);
          switch (escaped) {
            case '"', '\\', '/' -> result.append(escaped);
            case 'b' -> result.append('\b');
            case 'f' -> result.append('\f');
            case 'n' -> result.append('\n');
            case 'r' -> result.append('\r');
            case 't' -> result.append('\t');
            case 'u' -> appendUnicode(result);
            default -> fail("Malformed JSON string escape");
          }
        } else {
          if (current < 0x20) fail("Malformed JSON control character");
          result.append(current);
        }
      }
      fail("Malformed JSON: unterminated string");
      return "";
    }

    private void appendUnicode(StringBuilder result) {
      int first = hexCodeUnit();
      char firstChar = (char) first;
      if (Character.isHighSurrogate(firstChar)) {
        if (offset + 2 > input.length() || input.charAt(offset) != '\\' || input.charAt(offset + 1) != 'u')
          fail("Malformed JSON surrogate pair");
        offset += 2;
        char secondChar = (char) hexCodeUnit();
        if (!Character.isLowSurrogate(secondChar)) fail("Malformed JSON surrogate pair");
        result.appendCodePoint(Character.toCodePoint(firstChar, secondChar));
      } else if (Character.isLowSurrogate(firstChar)) {
        fail("Malformed JSON surrogate pair");
      } else {
        result.append(firstChar);
      }
    }

    private int hexCodeUnit() {
      if (offset + 4 > input.length()) fail("Malformed JSON unicode escape");
      int result = 0;
      for (int index = 0; index < 4; index++) {
        int digit = Character.digit(input.charAt(offset++), 16);
        if (digit < 0) fail("Malformed JSON unicode escape");
        result = result * 16 + digit;
      }
      return result;
    }

    private Object literal(String token, Object value) {
      if (!input.startsWith(token, offset)) fail("Malformed JSON literal");
      offset += token.length();
      return value;
    }

    private BigDecimal number() {
      int start = offset;
      if (consume('-')) {}
      if (consume('0')) {
        if (offset < input.length() && Character.isDigit(input.charAt(offset))) fail("Malformed JSON number");
      } else {
        digits();
      }
      if (consume('.')) digits();
      if (offset < input.length() && (input.charAt(offset) == 'e' || input.charAt(offset) == 'E')) {
        offset++;
        if (offset < input.length() && (input.charAt(offset) == '+' || input.charAt(offset) == '-')) offset++;
        digits();
      }
      try {
        return new BigDecimal(input.substring(start, offset));
      } catch (NumberFormatException failure) {
        fail("Malformed JSON number");
        return BigDecimal.ZERO;
      }
    }

    private void digits() {
      int start = offset;
      while (offset < input.length() && Character.isDigit(input.charAt(offset))) offset++;
      if (start == offset) fail("Malformed JSON number");
    }

    private void whitespace() {
      while (offset < input.length() && " \t\r\n".indexOf(input.charAt(offset)) >= 0) offset++;
    }

    private boolean consume(char expected) {
      if (offset < input.length() && input.charAt(offset) == expected) {
        offset++;
        return true;
      }
      return false;
    }

    private void require(char expected) {
      if (!consume(expected)) fail("Malformed JSON: expected " + expected);
    }
  }
}
