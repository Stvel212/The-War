# remove the two permanent "Набор на турнир 2x2" text tags (stale announcement from 1.6aa)
use utf8; local $/; my $f = shift; open(my $h, '<:encoding(UTF-8)', $f) or die; my $s = <$h>; close $h;
my $n = ($s =~ s|    tt = CreateTextTag\(\)\r?\n    SetTextTagText\(tt, "Набор на турнир 2x2[^\n]*\r?\n    tt\.setPos\([^\n]*\r?\n    tt\.setColor\([^\n]*\r?\n    tt\.setPermanent\(true\)\r?\n    SetTextTagVisibility\(tt, true\)\r?\n||g);
$s =~ s|(    a = a \+ 1\r?\n)(\r?\n)+(public function initTrig_Int2)|$1    // объявление о турнире 2x2 (два постоянных текстага у воды) убрано по просьбе\n\n$3| if $n;
open($h, '>:encoding(UTF-8)', $f) or die; print $h $s; close $h; print "removed $n text tags\n";
