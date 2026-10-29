# bugfix ZapretWater: the original RemoveRect(r) destroyed the global base rect (rct_BazaN); the next unit
# entering a Stop strip then got centerY = 0 and was teleported to the map centre (worked by accident in classic).
use utf8; local $/; my $f = shift; open(my $h, '<:encoding(UTF-8)', $f) or die; my $s = <$h>; close $h;
$s =~ s|(    SetUnitPosition\(u, x\.toReal\(\), r\.getCenterY\(\)\)\r?\n)    r\.remove\(\)\r?\n|$1    // В оригинале здесь был RemoveRect(r) — он уничтожал глобальный регион базы (rct_BazaN), и следующий юнит,\n    // вошедший в полосу Stop, получал centerY = 0 и телепортировался в центр карты. В классике это «работало»\n    // (память удалённого rect'а ещё хранила координаты), в Reforged — нет.\n|;
open($h, '>:encoding(UTF-8)', $f) or die; print $h $s; close $h;
