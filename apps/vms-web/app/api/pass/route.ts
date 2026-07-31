/**
 * Renders a visitor's pass as a PNG (US-09.5.1).
 *
 * The QR payload is fetched here, on the portal server, and turned into an image. The browser gets
 * the image and never the payload as a value — which is what AC-3 asks for. It is a POST rather
 * than a GET because the response is the thing that opens a barrier: a GET would end up in browser
 * history, in a referrer, and in any proxy log between here and the screen.
 *
 * Why the portal renders this at all: no QR library is obtainable for the JVM from this network
 * (Maven Central answers 403), while the portal's npm registry is reachable. Recorded as a
 * deviation, not a design preference — a JVM-side renderer replaces this the day the jar resolves.
 */
import { NextResponse } from "next/server";
import QRCode from "qrcode";
import { API_URL_SERVER } from "@/lib/config";

export async function POST(request: Request): Promise<Response> {
  const auth = request.headers.get("authorization");
  if (!auth) {
    return NextResponse.json({ error: "unauthenticated" }, { status: 401 });
  }

  let visitorId: unknown;
  try {
    ({ visitorId } = await request.json());
  } catch {
    return NextResponse.json({ error: "invalid_body" }, { status: 400 });
  }
  if (typeof visitorId !== "string" || !visitorId) {
    return NextResponse.json({ error: "invalid_body" }, { status: 400 });
  }

  // The caller's own token is forwarded rather than a service credential: the API decides whether
  // this person may see this pass, exactly as it would if the browser had asked directly.
  const upstream = await fetch(
    `${API_URL_SERVER}/api/v1/visitors/${encodeURIComponent(visitorId)}/credential`,
    { headers: { authorization: auth }, cache: "no-store" },
  );

  if (!upstream.ok) {
    // The upstream problem body is passed through, but never the payload — there isn't one on a
    // failure, and forwarding the whole body on success is exactly what this route exists to avoid.
    const problem = await upstream.json().catch(() => ({ error: "pass_unavailable" }));
    return NextResponse.json(problem, { status: upstream.status });
  }

  const pass = (await upstream.json()) as { qrPayload?: string };
  if (!pass.qrPayload) {
    return NextResponse.json({ error: "no_payload" }, { status: 409 });
  }

  const png = await QRCode.toBuffer(pass.qrPayload, {
    type: "png",
    width: 320,
    margin: 2,
    errorCorrectionLevel: "M",
  });

  return new NextResponse(new Uint8Array(png), {
    status: 200,
    headers: {
      "Content-Type": "image/png",
      // Never cached, anywhere: the image is as sensitive as the payload behind it.
      "Cache-Control": "no-store, private",
    },
  });
}
