BEGIN { FS = "\t" }
FILENAME == "jf_units.tsv" { jk[$1] = $2; jb[$1] = $3; jh[$1] = $4; jm[$1] = tolower($5); jn[$1] = $6; next }
FILENAME == "jf_trains.tsv" { tr[$1] = $2; next }
FILENAME == "war_units.tsv" { wb[$1] = $3; wh[$1] = $4; wm[$1] = tolower($5); wn[$1] = $6; next }
FILENAME == "war_cand.txt" { a[++na] = $1; next }
FILENAME == "war_all.txt" { all[++nb] = $1; next }
function score(id, c,   d) { if (jh[id] == "-" && wh[c] == "-") return 0; if (jh[id] == "-" || wh[c] == "-") return 300; d = jh[id] - wh[c]; return d < 0 ? -d : d }
function pick(id, useAll, byModel,   i, c, n, s, best, bs) { best = ""; bs = 1e9; n = useAll ? nb : na
  for (i = 1; i <= n; i++) { c = useAll ? all[i] : a[i]
    if ((!byModel && wb[c] == jb[id]) || (byModel && jm[id] != "-" && wm[c] == jm[id])) { s = score(id, c); if (s < bs) { bs = s; best = c } } }
  return best }
FILENAME == "jf_ids.txt" {
  src = $1; id = src; if (jk[id] == "B" && (id in tr)) id = tr[id]
  how = "base"; best = pick(id, 0, 0)
  if (best == "") { how = "base-all"; best = pick(id, 1, 0) }
  if (best == "") { how = "model"; best = pick(id, 0, 1) }
  if (best == "") { how = "model-all"; best = pick(id, 1, 1) }
  if (best == "") how = "none"
  print src "\t" id "\t" jb[id] "\t" jh[id] "\t" jn[id] "\t" how "\t" best "\t" wb[best] "\t" wh[best] "\t" wn[best]
}
