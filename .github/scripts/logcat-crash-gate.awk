# Fail (exit 1) when a FATAL EXCEPTION belongs to me.zhanghai.android.files.
# `exit 1` in the main block still runs END, so the result is stored in `found`
# and END is the only place that calls exit. Unset `found` is treated as 0.
/FATAL EXCEPTION/ { fatal = 1; next }
fatal && /Process: me[.]zhanghai[.]android[.]files($|[^A-Za-z0-9_.])/ { found = 1; exit }
fatal && /Process:/ { fatal = 0 }
END { exit found }
