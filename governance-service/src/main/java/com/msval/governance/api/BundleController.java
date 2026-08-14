package com.msval.governance.api;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.msval.governance.persist.TopologyRepo;
import com.msval.governance.registry.RegistryService;
import com.msval.governance.support.HttpError;

/** DD-016 / W5 — bundle fetch (IF-005), publish/activate + declared topology (IF-006). Thin. */
@RestController
@RequestMapping("/api/v1")
public class BundleController {

    private final RegistryService registry;
    private final TopologyRepo topology;

    public BundleController(RegistryService registry, TopologyRepo topology) {
        this.registry = registry;
        this.topology = topology;
    }

    @GetMapping("/bundles/active")
    public ResponseEntity<ObjectNode> active(@RequestParam String stage) {
        RegistryService.BundleView view = registry.active(stage);
        return ResponseEntity.ok().eTag("\"" + view.version() + "\"").body(bundleJson(view));
    }

    @GetMapping("/bundles/version")
    public ObjectNode versions() {
        ObjectNode out = Json.object();
        ObjectNode sv = out.putObject("stage_versions");
        registry.stageVersions().forEach(sv::put);
        return out;
    }

    @GetMapping("/bundles/{version}")
    public ObjectNode byVersion(@PathVariable String version, @RequestParam String stage) {
        return bundleJson(registry.byVersion(version, stage));
    }

    @PostMapping("/bundles")
    public ResponseEntity<ObjectNode> publish(@RequestBody JsonNode bundle) {
        RegistryService.PublishResult r = registry.publish(bundle);
        ObjectNode out = Json.object();
        out.put("version", r.version());
        return ResponseEntity.status(r.created() ? 201 : 200).body(out);
    }

    @PostMapping("/bundles/{version}/activate")
    public ObjectNode activate(@PathVariable String version) {
        Map<String, String> stageVersions = registry.activate(version);
        ObjectNode out = Json.object();
        ObjectNode sv = out.putObject("stage_versions");
        stageVersions.forEach(sv::put);
        return out;
    }

    /** IF-006: declared topology snapshot for drift comparison (REQ-014). */
    @PostMapping("/topology/declared")
    public ResponseEntity<ObjectNode> declareTopology(@RequestBody JsonNode body) {
        String environment = body.path("environment").asText("");
        JsonNode graph = body.get("graph");
        if (environment.isEmpty() || graph == null || !graph.isObject()
                || !graph.path("nodes").isArray()) {
            throw HttpError.unprocessable("body must be {environment, graph:{nodes:[…], edges:[…]}}",
                    List.of());
        }
        long id = topology.append(environment, "declared", graph);
        ObjectNode out = Json.object();
        out.put("snapshot_id", id);
        return ResponseEntity.status(201).body(out);
    }

    private static ObjectNode bundleJson(RegistryService.BundleView view) {
        ObjectNode out = Json.object();
        out.put("version", view.version());
        if (view.manifest() != null) {
            out.set("manifest", view.manifest());
        }
        ArrayNode rules = out.putArray("rules");
        view.rules().forEach(rules::add);
        return out;
    }
}
