# Persian word list — sources and licence

`fa_words.tsv` (`word<TAB>count`, 100,000 most frequent words) is used by `PersianSpelling` to correct
speech-recognition spelling offline. It is an adaptation of two frequency lists and is distributed under
**CC BY-SA 4.0** (https://creativecommons.org/licenses/by-sa/4.0/), the licence of the second source.

| Source | Licence | Link |
|---|---|---|
| Leipzig Corpora Collection, Iranian Persian community corpus `pes_community_2017` (word frequencies). D. Goldhahn, T. Eckart, U. Quasthoff: *Building Large Monolingual Dictionaries at the Leipzig Corpora Collection: From 100 to 200 Languages*, LREC 2012 | CC BY | https://wortschatz.uni-leipzig.de/en/download |
| FrequencyWords, Persian 2018 list (`fa_full.txt`) by Hermit Dave, derived from OpenSubtitles (http://www.opensubtitles.org) | CC BY-SA 4.0 | https://github.com/hermitdave/FrequencyWords |

Changes made: Arabic letter forms mapped to Persian (ي→ی, ك→ک, ى→ی, ة/ۀ→ه), diacritics, tatweel and
zero-width joiners removed, repeated half-spaces collapsed, Unicode NFC; only tokens made of Persian
letters and half-spaces kept; counts summed (subtitle counts weighted ×2 so spoken forms are represented);
the 100,000 most frequent words kept.
