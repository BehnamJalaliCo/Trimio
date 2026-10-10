# Signature — approved visual language (v1)

`signature-v1.png` is the approved board (owner sign-off, phase 1). `source/` holds the HTML
mock-up it was rendered from; open it next to a local copy of the brand fonts in `source/fonts/`
(not in git) to iterate.

Implementation: `core/designsystem/.../signature` (`SignatureTheme`, `Sig.colors`, `Sig.type`,
components). Every screen is checked against its mock-up by rendering the Compose version at the
same size (`SignatureHomeTest`); `signature-home-check.png` is mock-up (left) vs Compose (right).

Accent rules — one meaning each, never mixed:
- ember `action`: what the user can do (buttons, brand dot)
- lime `emphasis`: what matters inside content (emphasised words, live state)
- iridescent `director`: only where the AI director is present
- text and glass over footage always use `onMedia` / `overMedia`, in both themes
