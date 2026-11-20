# [06_Aura] type=ticket period_end=2026-11-17 status=green blockers=0

## US-001 — z7 NPU/HTP device survey (mission-20261116-215738)

Executed 2026-11-17 ~00:50 CT on z7 (RFCY71BMBRE) by controller directly after four consecutive worker-session timeouts (900/900/660/660s, zero artifacts) — engine starvation ruling in progress.txt. Raw outputs: `repro\npu\survey_raw_z7.txt`.

## Findings
1. **SoC**: `ro.soc.model = SM8750`, `ro.hardware = qcom` (Snapdragon 8 Elite for Galaxy, 11.1 GB).
2. **CDSP access path exists**: `/dev/fastrpc-cdsp` (char 10:288, owner system) present; SELinux label `vendor_qdsp_device:s0`. Nodes for secure adsp/cdsp present too.
3. **Vendor CDSP skels = image stack only** (`/vendor/lib/rfsa/adsp/`: jpege, hme, mctf, mlawb, binning — camera-era skels, 2021 timestamps). **No LLM/QNN-family skel on the vendor partition** ⇒ an LLM NPU path must ship its own skel inside an engine pack and point `ADSP_LIBRARY_PATH` at it (standard QNN deploy pattern; also exactly what the pack format in US-005 is shaped for).
4. **remote processor state**: NOT CHECKED — `/sys/class/remoteproc/*/state` Permission denied for shell (names spss/soccp visible; no root).
5. **HTP arch version**: NOT directly exposed by props (grep hexagon|htp|cdsp on getprop: empty). Plan ticket §P3 records z7 as HTP v68-class; byte-level confirmation happens when a v68 skel is load-tested (US-004) — recorded as HOLD pending pack, not assumed.
6. z8: NOT CHECKED (unattached; wireless pairing expired — one adb devices + mdns probe: only RFCY71BMBRE present).

## Verdict
Device side of the NPU lane is **plausible and gated by exactly one open question**: whether untrusted_app SELinux domain may open `fastrpc-cdsp` on this One UI build (shell cannot answer that; the honest probe is a minimal `open()` test running as the app uid — candidate first cell of US-004's device leg).

## Commands (reproducible)
Ġ
===== [a_fastrpc_nodes] ls -l /dev/ | grep -iE "fastrpc|adsprpc|hdroidnetlink"
crw-r--r--  1 system      system                10, 290 1970-01-17 21:11 fastrpc-adsp-secure
crw-rw-r--  1 system      system                10, 288 1970-01-17 21:11 fastrpc-cdsp
crw-r--r--  1 system      system                10, 287 1970-01-17 21:11 fastrpc-cdsp-secure

===== [b_remoteproc] for f in /sys/class/remoteproc/remoteproc*; do echo $f $(cat $f/state 2>&1) name=$(cat $f/name 2>/dev/null); done
/sys/class/remoteproc/remoteproc0 cat: /sys/class/remoteproc/remoteproc0/state: Permission denied name=188101c.remoteproc-spss
/sys/class/remoteproc/remoteproc1 cat: /sys/class/remoteproc/remoteproc1/state: Permission denied name=
/sys/class/remoteproc/remoteproc2 cat: /sys/class/remoteproc/remoteproc2/state: Permission denied name=
/sys/class/remoteproc/remoteproc3 cat: /sys/class/remoteproc/remoteproc3/state: Permission denied name=
/sys/class/remoteproc/remoteproc4 cat: /sys/class/remoteproc/remoteproc4/state: Permission denied name=a3380000.remoteproc-soccp

===== [c_props] getprop | grep -iE "hexagon|htp|cdsp" | head -20

===== [d_soc] getprop ro.soc.model; getprop ro.soc.version; getprop ro.hardware
SM8750

qcom

===== [e_vendor_skels] ls -l /vendor/lib/rfsa/adsp/ /vendor/dsp/cdsp/ 2>&1 | head -30
ls: /vendor/dsp/cdsp/: Permission denied
total 280
-rw-r--r-- 1 root root 122120 2021-12-31 18:00 libadsp_jpege_skel.so
-rw-r--r-- 1 root root  20736 2021-12-31 18:00 libdsp_streamer_binning.so
-rw-r--r-- 1 root root 116184 2021-12-31 18:00 libhme_dsp_skel.so
-rw-r--r-- 1 root root  91336 2021-12-31 18:00 libmctfengine_skel.so
-rw-r--r-- 1 root root  62796 2021-12-31 18:00 libmlawbpostproc_dsp_skel.so

===== [f_appdomain] id u0_a123 2>/dev/null; ls -Z /dev/fastrpc-cdsp 2>&1
uid=10123(u0_a123) gid=10123(u0_a123) groups=10123(u0_a123) context=u:r:shell:s0
u:object_r:vendor_qdsp_device:s0 /dev/fastrpc-cdsp

Ġ


## Amendment (controller, 2026-11-17): HTP-version evidence hunt, closed negative
AC demands the HTP version with a proving command. All sanctioned sources attempted:
- `getprop | grep -iE "hexagon|htp|cdsp|v7[0-9]"` -> empty (re-run twice, raw in repro\npu\survey_raw_z7.txt).
- remoteproc names readable (`spss`, `soccp`; no cdsp entry), `state` Permission denied (no root).
- Upstream at fork pin 4284599ac: `docs/ops/Hexagon.csv` fetched via contents API = **0 bytes** (placeholder at this commit); `docs/ops/Hexagon.md` = 404 (no such file at pin).
- Hexagon SDK arch mapping (SM8750 -> HTP gen) lives in Qualcomm docs behind login — not yet a sanctioned local artifact (US-002 owner blocker).
Conclusion: HTP version **not determinable from sanctioned evidence today**. Positive confirmation paths: (a) SDK memo after owner upload; (b) first real skel load on z7 (US-004/US-008) — a v73-built skel loading IS the proof. Held open rather than filled with a recalled number.
