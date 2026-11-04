You are transcribing a page of an industrial datasheet ("Series 76 Electric Actuators — Electrical Data").
The page is an image containing one or more data tables. Transcribe them EXACTLY into Markdown.
Accuracy of every digit is critical: these numbers are used for engineering decisions.

## Output format (follow strictly, output nothing else)

For each table on the page, in order of appearance:

```
## <ENCLOSURE HEADING> | <POWER HEADING>

| <column> | <column> | ... |
|---|---|...|
| <row> ... |
```

- `<ENCLOSURE HEADING>`: the red heading above the table, verbatim (e.g. `WEATHERPROOF - CSA, CE, & UKCA`, `EXPLOSIONPROOF - CSA`).
- `<POWER HEADING>`: the small bold caption right above the table, verbatim (e.g. `110V - Single Phase Power`, `220V - 3 Phase Power`, `24V AC/DC Power - On/Off`, `24V DC Power - Modulating`).

After all tables, if the page has footnotes or a NOTES block, add:

```
### Notes
- <one bullet per note line, verbatim; keep the original numbering text; nested bullets flattened>
```
Include footnotes that appear right under a table (e.g. lines starting with `*`).

## Column names

Use ONLY the columns that exist in the table, named with this exact vocabulary and in the table's order:

| Use this name | When the table shows |
|---|---|
| `Base Part Number` | Base Part Number |
| `On/Off Torque (in-lbs)`, `On/Off Torque (Nm)` | On/Off Output Torque → In-Lbs / Nm |
| `On/Off Duty Cycle (S4%)` | On/Off Duty Cycle |
| `Cycles per Hour` | Cycles Per Hour |
| `Modulating Torque (in-lbs)`, `Modulating Torque (Nm)` | Modulating Output Torque → In-Lbs / Nm |
| `Modulating Duty Cycle (S4%)` | Modulating Duty Cycle |
| `Starts per Hour` | Starts Per Hour |
| `Torque (in-lbs)`, `Torque (Nm)` | plain "Output Torque" (no On/Off or Modulating prefix) |
| `Duty Cycle (S4%)` | plain "Duty Cycle" (no prefix) |
| `Speed 60Hz (s)`, `Speed 50Hz (s)` | Operating Speed (sec) split into 60 Hz / 50 Hz |
| `Speed (s)` | Operating Speed with a single column |
| `FLA 60Hz (A)`, `FLA 50Hz (A)` / `FLA (A)` | Full Load Current, split by frequency / single column |
| `LRA 60Hz (A)`, `LRA 50Hz (A)` / `LRA (A)` | Locked Rotor Current, split by frequency / single column |
| `Motor Power (W)` | Motor Power (Watts) |

## Cell rules

1. One Markdown row per part number. Never skip, merge or reorder rows.
2. Part numbers: plain text, no bold, exactly as printed (e.g. `761A00-11300000/A`). Keep a trailing `*` if printed (e.g. `766A00-11320000/A*`).
3. Numbers: copy exactly as printed, including thousands separators and decimals (`1,330`, `11.2`, `17.0`). Do not convert units, round or compute anything.
4. Merged cells (e.g. Motor Power spanning several rows): repeat the value in EVERY row it spans.
5. If a row says `N/A` (or a block of rows is covered by a single `N/A`), write `N/A` in every data cell of those rows (including Motor Power if no value is printed for them).
6. Never leave a cell empty and never invent a value. If a digit is truly unreadable, write `??`.
