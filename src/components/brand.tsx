"use client";

import Link from "next/link";
import { motion } from "motion/react";

export function Brand({ compact = false, active = false }: { compact?: boolean; active?: boolean }) {
  return (
    <Link className="brand" href="/" aria-label="Sovereignty Lens home">
      <motion.span
        className="brand-mark"
        aria-hidden="true"
        animate={active ? { scale: [1, 1.08, 1] } : { scale: 1 }}
        transition={{ duration: 0.7, ease: [0.16, 1, 0.3, 1] }}
      >
        <i />
        <i />
        <i />
      </motion.span>
      {!compact && <strong>Sovereignty Lens</strong>}
    </Link>
  );
}
