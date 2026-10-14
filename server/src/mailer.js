/**
 * Sends account emails (verification and password-reset codes).
 *
 *  - RESEND_API_KEY set: sends through Resend (https://resend.com), from MAIL_FROM.
 *  - Otherwise, development mode: the email is printed to the server log (`wrangler tail` on
 *    Cloudflare), kept in the dev_mail table when DEV_MAIL=1 (read back only in TEST_MODE), and
 *    handed to `devSink` if given (Node writes it to READER_DEV_MAIL_DIR). Nothing is sent.
 */
export function createMailer({ env, store, log = console.log, devSink, now = Date.now }) {
  if (env.RESEND_API_KEY) {
    const from = env.MAIL_FROM || 'Reader <onboarding@resend.dev>';
    return {
      kind: 'resend',
      async send({ to, subject, text }) {
        const res = await fetch('https://api.resend.com/emails', {
          method: 'POST',
          headers: { Authorization: `Bearer ${env.RESEND_API_KEY}`, 'Content-Type': 'application/json' },
          body: JSON.stringify({ from, to, subject, text }),
          signal: AbortSignal.timeout(10_000),
        });
        if (!res.ok) throw new Error(`Resend: HTTP ${res.status} ${await res.text()}`);
      },
    };
  }
  return {
    kind: 'dev',
    async send(mail) {
      log(`[dev mail] to=${mail.to} subject="${mail.subject}"\n${mail.text}`);
      if (env.DEV_MAIL === '1') await store.saveDevMail(mail, now());
      if (devSink) await devSink(mail);
    },
  };
}
