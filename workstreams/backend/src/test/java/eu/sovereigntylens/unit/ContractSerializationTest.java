package eu.sovereigntylens.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import eu.sovereigntylens.contract.ApiErrorCode;
import eu.sovereigntylens.contract.ApiErrorResponse;
import eu.sovereigntylens.contract.ContributionRequest;
import eu.sovereigntylens.contract.DependencyCreatedEvent;
import eu.sovereigntylens.contract.GraphEvent;
import eu.sovereigntylens.contract.GraphInvalidatedEvent;
import eu.sovereigntylens.contract.GraphNode;
import eu.sovereigntylens.contract.GraphSnapshot;
import eu.sovereigntylens.domain.DomainException;
import eu.sovereigntylens.domain.service.Normalizer;
import eu.sovereigntylens.fixture.Fixtures;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * The wire format itself, checked against contracts/data-contract.md.
 *
 * <p>The mapper is built to match what {@code application.yml} configures for the running service:
 * a test that serialized through a default {@code ObjectMapper} would pass while production emitted
 * something else.
 */
@DisplayName("Contract serialization")
class ContractSerializationTest {

  private final ObjectMapper json = productionLikeMapper();

  /**
   * Mirrors the {@code spring.jackson} block of application.yml on top of the same builder Spring
   * Boot itself starts from, so the well-known modules - Java time in particular - are registered
   * exactly as they are at runtime.
   */
  private static ObjectMapper productionLikeMapper() {
    return Jackson2ObjectMapperBuilder.json()
        .featuresToEnable(
            DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
            DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .featuresToDisable(
            SerializationFeature.WRITE_DATES_AS_TIMESTAMPS,
            SerializationFeature.WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS)
        .serializationInclusion(JsonInclude.Include.ALWAYS)
        .build();
  }

  /** Every shape the API can put on the wire, paired with the type it must deserialize back to. */
  static Stream<Arguments> everyContractShape() {
    return Stream.of(
        Arguments.of("GraphSnapshot (seed)", Fixtures.seedSnapshot(), GraphSnapshot.class),
        Arguments.of(
            "GraphSnapshot (with contribution)",
            Fixtures.snapshotWithContribution(),
            GraphSnapshot.class),
        Arguments.of(
            "SessionSummary",
            Fixtures.sessionSummary(),
            eu.sovereigntylens.contract.SessionSummary.class),
        Arguments.of("GraphNode", Fixtures.contributedNode(), GraphNode.class),
        Arguments.of(
            "GraphEdge", Fixtures.contributedEdge(), eu.sovereigntylens.contract.GraphEdge.class),
        Arguments.of(
            "ContributionRequest", Fixtures.contributionRequest(), ContributionRequest.class),
        Arguments.of(
            "ContributionResult",
            Fixtures.contributionResult(),
            eu.sovereigntylens.contract.ContributionResult.class),
        Arguments.of("ApiErrorResponse (with field)", Fixtures.validationError(), ApiErrorResponse.class),
        Arguments.of("ApiErrorResponse (internal)", Fixtures.internalError(), ApiErrorResponse.class),
        Arguments.of(
            "AdminLoginRequest",
            Fixtures.adminLoginRequest(),
            eu.sovereigntylens.contract.AdminLoginRequest.class),
        Arguments.of(
            "AdminLoginResult",
            Fixtures.adminLoginResult(),
            eu.sovereigntylens.contract.AdminLoginResult.class),
        Arguments.of(
            "AdminActionResult",
            Fixtures.adminActionResult(),
            eu.sovereigntylens.contract.AdminActionResult.class),
        Arguments.of(
            "AdminDependency",
            Fixtures.adminDependency(),
            eu.sovereigntylens.contract.AdminDependency.class),
        Arguments.of(
            "AdminDependencyList",
            Fixtures.adminDependencyList(),
            eu.sovereigntylens.contract.AdminDependencyList.class),
        Arguments.of(
            "DependencyStatusRequest",
            Fixtures.dependencyStatusRequest(),
            eu.sovereigntylens.contract.DependencyStatusRequest.class),
        Arguments.of(
            "DependencyStatusResult",
            Fixtures.dependencyStatusResult(),
            eu.sovereigntylens.contract.DependencyStatusResult.class),
        Arguments.of(
            "DependencyCreatedEvent",
            Fixtures.dependencyCreatedEvent(),
            DependencyCreatedEvent.class),
        Arguments.of(
            "GraphInvalidatedEvent",
            Fixtures.graphInvalidatedEvent(),
            GraphInvalidatedEvent.class));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("everyContractShape")
  void roundTripsWithoutLosingAField(String name, Object value, Class<?> type) throws Exception {
    String encoded = json.writeValueAsString(value);

    assertThat(json.readValue(encoded, type)).isEqualTo(value);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("everyContractShape")
  void usesCamelCaseFieldNamesThroughout(String name, Object value, Class<?> type)
      throws Exception {
    // The contract fixes camelCase on the wire while the database uses snake_case; a mapper
    // naming strategy applied by accident would show up here first.
    assertThat(fieldNames(json.valueToTree(value)))
        .allSatisfy(field -> assertThat(field).matches("[a-z][a-zA-Z0-9]*"));
  }

  @Nested
  @DisplayName("scalars")
  class Scalars {

    @Test
    void serializesEnumsAsTheirSnakeCaseWireToken() throws Exception {
      JsonNode node = json.valueToTree(Fixtures.contributedNode());

      assertThat(node.get("organizationType").asText()).isEqualTo("cloud");
      assertThat(node.get("jurisdiction").asText()).isEqualTo("united_states");
      assertThat(json.valueToTree(Fixtures.sessionSummary()).get("status").asText())
          .isEqualTo("open");
      assertThat(json.valueToTree(Fixtures.contributedEdge()).get("status").asText())
          .isEqualTo("active");
      assertThat(json.valueToTree(Fixtures.graphInvalidatedEvent()).get("reason").asText())
          .isEqualTo("pause");
    }

    @Test
    void readsEnumsBackFromTheirWireToken() throws Exception {
      GraphNode node =
          json.readValue(
              """
              {"id":"n","name":"N","organizationType":"consulting",
               "jurisdiction":"unknown","isSeed":false}
              """,
              GraphNode.class);

      assertThat(node.organizationType())
          .isEqualTo(eu.sovereigntylens.contract.OrganizationType.CONSULTING);
      assertThat(node.jurisdiction()).isEqualTo(eu.sovereigntylens.contract.Jurisdiction.UNKNOWN);
    }

    @Test
    void rejectsAnEnumTokenThatIsNotInTheContract() {
      assertThatThrownBy(
              () ->
                  json.readValue(
                      "{\"id\":\"n\",\"name\":\"N\",\"organizationType\":\"telco\","
                          + "\"jurisdiction\":\"unknown\",\"isSeed\":false}",
                      GraphNode.class))
          .isInstanceOf(JsonProcessingException.class);
    }

    @Test
    void serializesInstantsAsRfc3339StringsRatherThanNumbers() throws Exception {
      JsonNode edge = json.valueToTree(Fixtures.contributedEdge());

      assertThat(edge.get("createdAt").isTextual()).isTrue();
      assertThat(edge.get("createdAt").isNumber()).isFalse();
      assertThat(edge.get("createdAt").asText()).isEqualTo("2026-03-01T10:15:30Z");
    }

    @Test
    void serializesEverySnapshotAndEventTimestampAsAString() throws Exception {
      assertThat(json.valueToTree(Fixtures.seedSnapshot()).get("serverTime").isTextual()).isTrue();
      assertThat(
              json.valueToTree(Fixtures.dependencyCreatedEvent()).get("occurredAt").isTextual())
          .isTrue();
    }

    @Test
    void stampsContractVersionOneOnEveryEnvelope() throws Exception {
      for (Object envelope :
          List.of(
              Fixtures.seedSnapshot(),
              Fixtures.contributionResult(),
              Fixtures.validationError(),
              Fixtures.adminActionResult(),
              Fixtures.adminDependencyList(),
              Fixtures.dependencyStatusResult(),
              Fixtures.adminLoginResult(),
              Fixtures.dependencyCreatedEvent(),
              Fixtures.graphInvalidatedEvent())) {
        assertThat(json.valueToTree(envelope).get("contractVersion").asInt())
            .describedAs(envelope.getClass().getSimpleName())
            .isEqualTo(1);
      }
    }

    @Test
    void keepsTheSeedFlagNamedIsSeedAsTheContractSpellsIt() {
      assertThat(json.valueToTree(Fixtures.contributedNode()).has("isSeed")).isTrue();
      assertThat(json.valueToTree(Fixtures.contributedEdge()).has("isSeed")).isTrue();
    }
  }

  @Nested
  @DisplayName("error envelope")
  class ErrorEnvelope {

    @Test
    void omitsFieldEntirelyWhenTheFailureIsNotAttributableToOne() throws Exception {
      // The contract says optional fields are omitted, not serialized as null: a consumer that
      // checks for presence must not see a null-valued key.
      JsonNode error = json.valueToTree(Fixtures.internalError()).get("error");

      assertThat(error.has("field")).isFalse();
      assertThat(json.writeValueAsString(Fixtures.internalError())).doesNotContain("field");
    }

    @Test
    void includesFieldWhenTheFailureNamesOne() throws Exception {
      JsonNode error = json.valueToTree(Fixtures.validationError()).get("error");

      assertThat(error.get("field").asText()).isEqualTo("target.name");
    }

    @Test
    void carriesTheRetryableFlagOnEveryCode() throws Exception {
      for (ApiErrorResponse response : Fixtures.everyErrorShape()) {
        JsonNode error = json.valueToTree(response).get("error");

        assertThat(error.get("retryable").isBoolean()).isTrue();
        assertThat(error.get("retryable").asBoolean())
            .describedAs(error.get("code").asText())
            .isEqualTo(response.error().code() == ApiErrorCode.INTERNAL_ERROR);
      }
    }

    @Test
    void spellsEveryErrorCodeExactlyAsTheContractLists() throws Exception {
      List<String> codes =
          Fixtures.everyErrorShape().stream()
              .map(response -> json.valueToTree(response).get("error").get("code").asText())
              .toList();

      assertThat(codes)
          .containsExactly(
              "VALIDATION_ERROR",
              "UNAUTHORIZED",
              "FORBIDDEN",
              "SESSION_NOT_FOUND",
              "SOURCE_NOT_FOUND",
              "NOT_FOUND",
              "DUPLICATE_DEPENDENCY",
              "ALREADY_CONTRIBUTED",
              "SESSION_PAUSED",
              "ROUND_CAPACITY_REACHED",
              "INTERNAL_ERROR");
    }
  }

  @Nested
  @DisplayName("request strictness")
  class RequestStrictness {

    @Test
    void rejectsAContributionRequestCarryingAnUnknownProperty() {
      String withExtra =
          """
          {
            "contractVersion": 1,
            "anonymousClientId": "3f2504e0-4f89-41d3-9a0c-0305e82c3301",
            "sourceOrganizationId": "00000000-0000-4000-8000-000000000103",
            "target": {"name": "Northwind Cloud", "organizationType": "cloud",
                       "jurisdiction": "united_states"},
            "isAdmin": true
          }
          """;

      assertThatThrownBy(() -> json.readValue(withExtra, ContributionRequest.class))
          .isInstanceOf(UnrecognizedPropertyException.class);
    }

    @Test
    void rejectsAnUnknownPropertyNestedInsideTheTarget() {
      String withExtra =
          """
          {
            "contractVersion": 1,
            "anonymousClientId": "3f2504e0-4f89-41d3-9a0c-0305e82c3301",
            "sourceOrganizationId": "00000000-0000-4000-8000-000000000103",
            "target": {"name": "Northwind Cloud", "organizationType": "cloud",
                       "jurisdiction": "united_states", "isSeed": true}
          }
          """;

      assertThatThrownBy(() -> json.readValue(withExtra, ContributionRequest.class))
          .isInstanceOf(UnrecognizedPropertyException.class);
    }

    @Test
    void acceptsTheExactContractShape() throws Exception {
      String exact = json.writeValueAsString(Fixtures.contributionRequest());

      assertThat(json.readValue(exact, ContributionRequest.class))
          .isEqualTo(Fixtures.contributionRequest());
    }
  }

  /**
   * The bean-validation constraints on the request are the first gate a submission passes, before
   * the domain normalizer ever sees it. They therefore have to agree with the domain about what
   * "2-60 characters" means.
   */
  @Nested
  @DisplayName("request bean validation")
  class RequestBeanValidation {

    private final jakarta.validation.Validator validator =
        jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void acceptsTheCanonicalRequest() {
      assertThat(validator.validate(Fixtures.contributionRequest())).isEmpty();
    }

    @Test
    void rejectsABlankTargetName() {
      assertThat(validator.validate(requestNamed(" "))).isNotEmpty();
    }

    /**
     * Length is not a bean-validation concern any more.
     *
     * <p>{@code @Size} on a String counts UTF-16 units while the contract counts Unicode
     * characters, so it rejected a 31-character astral name as 62. {@link Normalizer} owns the rule
     * instead, counts code points, and applies it to the normalized form - which is the only form
     * worth measuring, since collapsing whitespace changes the length.
     */
    @Test
    void leavesNameLengthToTheDomainRatherThanMeasuringUtf16Units() {
      assertThat(validator.validate(requestNamed("a".repeat(61)))).isEmpty();

      assertThatThrownBy(() -> Normalizer.displayName("a".repeat(61)))
          .isInstanceOf(DomainException.class);
      assertThat(Normalizer.displayName("a".repeat(60))).hasSize(60);
    }

    @Test
    void acceptsUpToSixtyCharactersEvenWhenTheyAreAstral() {
      String sixtyCharacters = "😀".repeat(60);

      assertThat(sixtyCharacters.codePointCount(0, sixtyCharacters.length())).isEqualTo(60);
      assertThat(validator.validate(requestNamed(sixtyCharacters))).isEmpty();
      assertThat(Normalizer.displayName(sixtyCharacters)).isEqualTo(sixtyCharacters);
    }

    private ContributionRequest requestNamed(String name) {
      ContributionRequest canonical = Fixtures.contributionRequest();
      return new ContributionRequest(
          canonical.contractVersion(),
          canonical.anonymousClientId(),
          canonical.sourceOrganizationId(),
          new ContributionRequest.Target(
              name, canonical.target().organizationType(), canonical.target().jurisdiction()));
    }
  }

  @Nested
  @DisplayName("live events")
  class LiveEvents {

    @Test
    void choosesTheDependencyCreatedSubtypeFromTheDiscriminator() throws Exception {
      String encoded = json.writeValueAsString(Fixtures.dependencyCreatedEvent());

      GraphEvent event = json.readValue(encoded, GraphEvent.class);

      assertThat(event).isInstanceOf(DependencyCreatedEvent.class);
      assertThat(event).isEqualTo(Fixtures.dependencyCreatedEvent());
      assertThat(event.event()).isEqualTo("dependency.created");
    }

    @Test
    void choosesTheGraphInvalidatedSubtypeFromTheDiscriminator() throws Exception {
      String encoded = json.writeValueAsString(Fixtures.graphInvalidatedEvent());

      GraphEvent event = json.readValue(encoded, GraphEvent.class);

      assertThat(event).isInstanceOf(GraphInvalidatedEvent.class);
      assertThat(event).isEqualTo(Fixtures.graphInvalidatedEvent());
      assertThat(event.event()).isEqualTo("graph.invalidated");
    }

    @Test
    void rejectsAnUnknownDiscriminator() {
      String unknown =
          """
          {"contractVersion":1,"event":"dependency.deleted","eventId":"e","sessionSlug":"demo",
           "round":1,"occurredAt":"2026-03-01T10:15:30Z"}
          """;

      assertThatThrownBy(() -> json.readValue(unknown, GraphEvent.class))
          .isInstanceOf(InvalidTypeIdException.class);
    }

    @Test
    void keepsTheDiscriminatorVisibleAsAFieldOfTheDeserializedEvent() throws Exception {
      // The discriminator is an EXISTING_PROPERTY: it must survive the round trip, because the SSE
      // frame's event name and the payload's own field have to agree.
      JsonNode encoded = json.valueToTree(Fixtures.dependencyCreatedEvent());

      assertThat(encoded.get("event").asText()).isEqualTo("dependency.created");
      assertThat(json.readValue(encoded.toString(), GraphEvent.class).event())
          .isEqualTo("dependency.created");
    }

    @Test
    void keepsTheDerivedTopicOffTheWire() {
      // topic() is computed from two fields that are already on the wire. Serializing it would add
      // a property the contract does not declare, which a strict consumer schema rejects.
      assertThat(json.valueToTree(Fixtures.dependencyCreatedEvent()).has("topic")).isFalse();
      assertThat(json.valueToTree(Fixtures.graphInvalidatedEvent()).has("topic")).isFalse();
    }

    @Test
    void derivesTheTopicFromTheSessionSlugAndRound() {
      assertThat(Fixtures.dependencyCreatedEvent().topic()).isEqualTo("sovereignty:demo:round:1");
      assertThat(GraphEvent.topicFor("demo", 3)).isEqualTo("sovereignty:demo:round:3");
    }

    @Test
    void carriesTheSameCanonicalNodeAndEdgeAsTheHttpSuccessResponse() {
      // The contract requires a client that receives both to recognise them as one fact.
      assertThat(Fixtures.dependencyCreatedEvent().node())
          .isEqualTo(Fixtures.contributionResult().node());
      assertThat(Fixtures.dependencyCreatedEvent().edge())
          .isEqualTo(Fixtures.contributionResult().edge());
      assertThat(Fixtures.dependencyCreatedEvent().eventId())
          .isEqualTo(Fixtures.contributionResult().eventId());
    }
  }

  /** Every object field name anywhere in the tree, so nested records are covered too. */
  private static List<String> fieldNames(JsonNode node) {
    List<String> names = new ArrayList<>();
    collectFieldNames(node, names);
    return names;
  }

  private static void collectFieldNames(JsonNode node, List<String> names) {
    if (node.isObject()) {
      for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
        Map.Entry<String, JsonNode> entry = it.next();
        names.add(entry.getKey());
        collectFieldNames(entry.getValue(), names);
      }
    } else if (node.isArray()) {
      node.forEach(child -> collectFieldNames(child, names));
    }
  }
}
