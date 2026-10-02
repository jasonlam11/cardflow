/** Liveness for Docker; doesn't call backends so the dashboard can report their outages itself. */
export function GET() {
  return Response.json({ status: "UP" });
}
