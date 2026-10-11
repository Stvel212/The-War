/new AbilityDefinition[A-Za-z0-9]*\(\x27/ { match($0, /AbilityDefinition[A-Za-z0-9]*/); c=substr($0,RSTART+17,RLENGTH-17); match($0, /\x27[A-Za-z0-9]{4}\x27/); print substr($0,RSTART+1,4) "\t" c }
/createObjectDefinition\("w3a", \x27/ { match($0, /\x27[A-Za-z0-9]{4}\x27, *\x27[A-Za-z0-9]{4}\x27/); s=substr($0,RSTART,RLENGTH); gsub(/[\x27 ]/,"",s); split(s,a,","); print a[1] "\traw:" a[2] }
