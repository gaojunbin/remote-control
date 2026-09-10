/** Product mark: a small black rounded square, per the prototype. */
export function Mark({ size = 20 }: { size?: number }) {
  return (
    <span
      aria-hidden
      style={{
        width: size,
        height: size,
        borderRadius: Math.round(size * 0.3),
        background: 'var(--ink)',
        display: 'inline-block',
        flex: 'none',
      }}
    />
  );
}
