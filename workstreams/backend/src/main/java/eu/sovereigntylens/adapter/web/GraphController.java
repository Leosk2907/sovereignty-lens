package eu.sovereigntylens.adapter.web;

import eu.sovereigntylens.application.GraphQueryService;
import eu.sovereigntylens.contract.ApiErrorResponse;
import eu.sovereigntylens.contract.GraphSnapshot;
import eu.sovereigntylens.mapper.GraphMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public graph read.
 *
 * <p>Every client treats this endpoint, not the live event stream, as the source of truth: events
 * only tell a client that something changed, and it comes back here to find out what the graph now
 * is. That makes a stale cached response the one failure mode that would desynchronise the room, so
 * the response is explicitly uncacheable.
 */
@RestController
@Tag(name = "Graph", description = "Authoritative public read of a session's dependency graph")
public class GraphController {

  private final GraphQueryService graphQuery;

  public GraphController(GraphQueryService graphQuery) {
    this.graphQuery = graphQuery;
  }

  @Operation(
      summary = "Public graph snapshot",
      description =
          "Returns seed dependencies plus active audience dependencies from the session's current"
              + " round, every organization those reference, and the session root organization."
              + " Nodes and edges are ordered by creation time then id. All data is simulated,"
              + " unverified demo data produced by a live audience.")
  @ApiResponse(
      responseCode = "200",
      description = "The current snapshot",
      content = @Content(schema = @Schema(implementation = GraphSnapshot.class)))
  @ApiResponse(
      responseCode = "404",
      description = "SESSION_NOT_FOUND: no session has this slug",
      content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
  @GetMapping(value = "/api/sessions/{slug}/graph", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<GraphSnapshot> graph(
      @Parameter(description = "Session slug, for example \"demo\"", example = "demo")
          @PathVariable
          String slug) {
    GraphSnapshot snapshot = GraphMapper.toContract(graphQuery.load(slug), Instant.now());
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(snapshot);
  }
}
