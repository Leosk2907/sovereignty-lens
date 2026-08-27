"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { getGraphSnapshot } from "@/lib/api-client";
import { graphEventSchema, type GraphSnapshot } from "@/lib/contracts";
import { analyzeGraph } from "@/lib/graph-analysis";
import { subscribeToGraph, type ConnectionState } from "@/lib/realtime";

export function useLiveGraph() {
  const [snapshot, setSnapshot] = useState<GraphSnapshot | null>(null);
  const [connection, setConnection] = useState<ConnectionState>("connecting");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [latestEdgeId, setLatestEdgeId] = useState<string | null>(null);
  const [revealPath, setRevealPath] = useState<string[]>([]);
  const snapshotRef = useRef<GraphSnapshot | null>(null);
  const processedEvents = useRef(new Set<string>());
  const reconcileTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const revealTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const latestTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const refresh = useCallback(async () => {
    try {
      const next = await getGraphSnapshot();
      snapshotRef.current = next;
      setSnapshot(next);
      setError(null);
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "Could not load the graph.");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const sessionSlug = snapshot?.session.slug;
  const currentRound = snapshot?.session.currentRound;

  useEffect(() => {
    if (!sessionSlug || !currentRound) return;

    const scheduleReconcile = (delay = 250) => {
      if (reconcileTimer.current) clearTimeout(reconcileTimer.current);
      reconcileTimer.current = setTimeout(() => void refresh(), delay);
    };

    const unsubscribe = subscribeToGraph(
      sessionSlug,
      currentRound,
      (rawEvent) => {
        const parsed = graphEventSchema.safeParse(rawEvent);
        if (!parsed.success) {
          scheduleReconcile(0);
          return;
        }
        const event = parsed.data;
        const previous = snapshotRef.current;
        if (!previous || event.sessionSlug !== previous.session.slug || event.round !== previous.session.currentRound) {
          scheduleReconcile(0);
          return;
        }
        if (processedEvents.current.has(event.eventId)) return;
        processedEvents.current.add(event.eventId);
        if (processedEvents.current.size > 300) {
          const oldest = processedEvents.current.values().next().value as string | undefined;
          if (oldest) processedEvents.current.delete(oldest);
        }

        if (event.event === "graph.invalidated") {
          scheduleReconcile(0);
          return;
        }

        const previousAnalysis = analyzeGraph(previous.nodes, previous.edges, previous.session.rootOrganizationId);
        const nodes = previous.nodes.some((node) => node.id === event.node.id)
          ? previous.nodes.map((node) => (node.id === event.node.id ? event.node : node))
          : [...previous.nodes, event.node];
        const edges = previous.edges.some((edge) => edge.id === event.edge.id)
          ? previous.edges.map((edge) => (edge.id === event.edge.id ? event.edge : edge))
          : [...previous.edges, event.edge];
        const next = { ...previous, nodes, edges, serverTime: event.occurredAt };
        snapshotRef.current = next;
        setSnapshot(next);
        setLatestEdgeId(event.edge.id);
        if (latestTimer.current) clearTimeout(latestTimer.current);
        latestTimer.current = setTimeout(() => setLatestEdgeId(null), 4200);

        const nextAnalysis = analyzeGraph(nodes, edges, previous.session.rootOrganizationId);
        const newlyExternal = [...nextAnalysis.reachableExternalIds].find(
          (id) => !previousAnalysis.reachableExternalIds.has(id),
        );
        if (newlyExternal) {
          setRevealPath(nextAnalysis.shortestPathTo(newlyExternal));
          if (revealTimer.current) clearTimeout(revealTimer.current);
          revealTimer.current = setTimeout(() => setRevealPath([]), 6000);
        }
        scheduleReconcile();
      },
      setConnection,
    );

    return () => {
      unsubscribe();
      if (reconcileTimer.current) clearTimeout(reconcileTimer.current);
    };
  }, [currentRound, sessionSlug, refresh]);

  useEffect(() => {
    if (connection !== "degraded") return;
    const poll = setInterval(() => void refresh(), 3000);
    return () => clearInterval(poll);
  }, [connection, refresh]);

  useEffect(() => () => {
    if (revealTimer.current) clearTimeout(revealTimer.current);
    if (latestTimer.current) clearTimeout(latestTimer.current);
  }, []);

  return { snapshot, connection, loading, error, latestEdgeId, revealPath, refresh };
}
