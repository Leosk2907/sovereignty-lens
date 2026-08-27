import type { Metadata } from "next";
import Link from "next/link";
import { Brand } from "@/components/brand";

export const metadata: Metadata = { title: "About" };

export default function AboutPage() {
  return (
    <main className="about-shell">
      <nav><Brand /><Link className="button secondary" href="/">Back to the live graph</Link></nav>
      <section className="about-hero">
        <span className="eyebrow">The idea</span>
        <h1>Europe cannot control dependencies it cannot see.</h1>
        <p>A direct European supplier can depend on another supplier, which depends on infrastructure controlled elsewhere. Sovereignty risk often lives several steps away from the public contract.</p>
      </section>
      <section className="about-grid">
        <article><span>01</span><h2>Map the chain</h2><p>Connect public bodies, suppliers, infrastructure, software, hardware, and ownership into one directed graph.</p></article>
        <article><span>02</span><h2>Reveal exposure</h2><p>Follow every dependency from the governmental root and highlight paths crossing European jurisdiction.</p></article>
        <article><span>03</span><h2>Make action possible</h2><p>Turn an invisible systemic risk into something procurement teams and policy makers can discuss.</p></article>
      </section>
      <section className="prototype-note">
        <div><span className="eyebrow">Prototype transparency</span><h2>This demonstration maps its own dependencies too.</h2></div>
        <div className="dependency-chips"><span>Vercel · hosting</span><span>Supabase · database & realtime</span><span>Open-source · Next.js & Cytoscape</span></div>
        <p>Audience submissions are simulated and unverified. A production system would require sourced procurement records, software bills of materials, corporate ownership data, evidence review, access control, and clear confidence levels.</p>
      </section>
    </main>
  );
}
