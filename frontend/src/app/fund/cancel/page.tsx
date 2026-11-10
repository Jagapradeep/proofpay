import Link from "next/link";

export default function FundCancelPage() {
  return (
    <div className="card mx-auto max-w-lg p-8 text-center">
      <h1 className="text-lg font-semibold">Payment not approved</h1>
      <p className="mt-2 text-sm text-muted">You left PayPal before approving. Nothing was held or charged.</p>
      <Link href="/" className="btn-ghost mt-5">Back to jobs</Link>
    </div>
  );
}
