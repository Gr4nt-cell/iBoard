# Preserve every existing family/alias; add an independent emoji fallback before stock emoji.
{ xml=xml $0 "\n" }
END {
    if (xml ~ /AuraEmojiSystem[.]ttf/) { printf "%s",xml; exit }
    if (xml !~ /<familyset([ \t\r\n>])/) exit 42
    n=split(xml,parts,"<"); first=0; target=0; comment=0
    for(i=2;i<=n;i++) {
        tag=parts[i]; sub(/>.*/,"",tag)
        if(comment || tag ~ /^!--/) {
            comment=(parts[i] !~ /-->/); continue
        }
        if(tag ~ /^family([ \t\r\n]|$)/ && tag !~ /(^|[ \t\r\n])name[ \t\r\n]*=/) {
            if(!first) first=i
            if(tag ~ /und-Zsye/ && !target) target=i
        }
    }
    if(!target) target=first
    if(!target) exit 43
    printf "%s",parts[1]
    for(i=2;i<=n;i++) {
        if(i==target) printf "<!-- AURA: independent fallback; retain stock font and signed updates -->\n<family lang=\"und-Zsye\"><font weight=\"400\" style=\"normal\">AuraEmojiSystem.ttf</font></family>\n"
        printf "<%s",parts[i]
    }
}
