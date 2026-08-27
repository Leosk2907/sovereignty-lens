package eu.sovereigntylens.unit;

import static org.assertj.core.api.Assertions.assertThat;

import eu.sovereigntylens.contract.ApiErrorCode;
import eu.sovereigntylens.domain.model.DomainErrorCode;
import eu.sovereigntylens.mapper.ErrorMapper;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("ErrorMapper")
class ErrorMapperTest {

  /**
   * The status table from contracts/data-contract.md, transcribed rather than derived. Deriving it
   * from {@link ApiErrorCode} would only assert that the enum equals itself; written out, a change
   * to either side has to be a deliberate contract decision.
   *
   * <p>Plain integers, matching the enum: the contract package is shared with three frontend
   * workstreams and deliberately carries no web-framework type.
   */
  private static final Map<ApiErrorCode, Integer> CONTRACT_STATUSES =
      Map.ofEntries(
          Map.entry(ApiErrorCode.VALIDATION_ERROR, 400),
          Map.entry(ApiErrorCode.UNAUTHORIZED, 401),
          Map.entry(ApiErrorCode.FORBIDDEN, 403),
          Map.entry(ApiErrorCode.SESSION_NOT_FOUND, 404),
          Map.entry(ApiErrorCode.SOURCE_NOT_FOUND, 404),
          Map.entry(ApiErrorCode.NOT_FOUND, 404),
          Map.entry(ApiErrorCode.DUPLICATE_DEPENDENCY, 409),
          Map.entry(ApiErrorCode.ALREADY_CONTRIBUTED, 409),
          Map.entry(ApiErrorCode.SESSION_PAUSED, 423),
          Map.entry(ApiErrorCode.ROUND_CAPACITY_REACHED, 429),
          Map.entry(ApiErrorCode.INTERNAL_ERROR, 500));

  /**
   * The expected translation, likewise written out. Iterating {@link DomainErrorCode#values()}
   * below is what makes a new domain code fail here instead of reaching a client as whatever the
   * switch happened to fall through to.
   */
  private static final Map<DomainErrorCode, ApiErrorCode> EXPECTED_TRANSLATION =
      new EnumMap<>(
          Map.ofEntries(
              Map.entry(DomainErrorCode.VALIDATION_ERROR, ApiErrorCode.VALIDATION_ERROR),
              Map.entry(DomainErrorCode.UNAUTHORIZED, ApiErrorCode.UNAUTHORIZED),
              Map.entry(DomainErrorCode.FORBIDDEN, ApiErrorCode.FORBIDDEN),
              Map.entry(DomainErrorCode.SESSION_NOT_FOUND, ApiErrorCode.SESSION_NOT_FOUND),
              Map.entry(DomainErrorCode.SOURCE_NOT_FOUND, ApiErrorCode.SOURCE_NOT_FOUND),
              Map.entry(DomainErrorCode.NOT_FOUND, ApiErrorCode.NOT_FOUND),
              Map.entry(DomainErrorCode.DUPLICATE_DEPENDENCY, ApiErrorCode.DUPLICATE_DEPENDENCY),
              Map.entry(DomainErrorCode.ALREADY_CONTRIBUTED, ApiErrorCode.ALREADY_CONTRIBUTED),
              Map.entry(DomainErrorCode.SESSION_PAUSED, ApiErrorCode.SESSION_PAUSED),
              Map.entry(
                  DomainErrorCode.ROUND_CAPACITY_REACHED, ApiErrorCode.ROUND_CAPACITY_REACHED),
              Map.entry(DomainErrorCode.INTERNAL_ERROR, ApiErrorCode.INTERNAL_ERROR)));

  @Test
  void coversEveryDomainErrorCodeSoANewOneCannotBeAddedUnmapped() {
    assertThat(DomainErrorCode.values())
        .describedAs("a new DomainErrorCode needs a decision here and in ErrorMapper")
        .allSatisfy(code -> assertThat(EXPECTED_TRANSLATION).containsKey(code));
  }

  @ParameterizedTest
  @EnumSource(DomainErrorCode.class)
  void translatesEveryDomainCodeToItsContractCounterpart(DomainErrorCode code) {
    assertThat(ErrorMapper.toContract(code)).isEqualTo(EXPECTED_TRANSLATION.get(code));
  }

  @ParameterizedTest
  @EnumSource(DomainErrorCode.class)
  void carriesTheHttpStatusTheDataContractFixesForThatCode(DomainErrorCode code) {
    ApiErrorCode contractCode = ErrorMapper.toContract(code);

    assertThat(contractCode.status()).isEqualTo(CONTRACT_STATUSES.get(contractCode));
  }

  @Test
  void assignsEveryContractCodeTheStatusTheTableFixes() {
    assertThat(ApiErrorCode.values())
        .allSatisfy(
            code -> assertThat(code.status()).isEqualTo(CONTRACT_STATUSES.get(code)));
  }

  @Test
  void producesTheExactStatusSequenceTheContractTableLists() {
    // 400, 401, 403, 404, 404, 404, 409, 409, 423, 429, 500 - in DomainErrorCode declaration order.
    assertThat(
            Arrays.stream(DomainErrorCode.values())
                .map(ErrorMapper::toContract)
                .map(ApiErrorCode::status)
                .toList())
        .containsExactly(400, 401, 403, 404, 404, 404, 409, 409, 423, 429, 500);
  }

  @Test
  void keepsTheContractPackageFreeOfAWebFrameworkType() {
    // The contract artefact is consumed by three frontend workstreams; a Spring type on its API
    // would put a web framework on their classpath.
    assertThat(ApiErrorCode.class.getDeclaredMethods())
        .filteredOn(method -> method.getName().equals("status"))
        .singleElement()
        .satisfies(method -> assertThat(method.getReturnType()).isEqualTo(int.class));
    assertThat(Arrays.stream(ApiErrorCode.class.getDeclaredFields()).map(f -> f.getType().getName()))
        .noneMatch(name -> name.startsWith("org.springframework"));
  }

  @Test
  void marksOnlyInternalErrorRetryable() {
    assertThat(ApiErrorCode.values())
        .filteredOn(ApiErrorCode::retryable)
        .containsExactly(ApiErrorCode.INTERNAL_ERROR);
  }

  @Test
  void reachesADistinctContractCodeForEveryDomainCode() {
    // A collapsed mapping would hide one failure behind another's message on stage.
    assertThat(Arrays.stream(DomainErrorCode.values()).map(ErrorMapper::toContract).toList())
        .doesNotHaveDuplicates();
  }
}
