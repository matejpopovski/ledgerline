| Accounts | Workers | Strategy | Commits/s (median) | Range | Retries per commit | p50 ms | p99 ms | Gave up |
| ---: | ---: | --- | ---: | --- | ---: | ---: | ---: | ---: |
| 2 | 1 | row lock | 175 | 164 to 204 | 0.000 | 5.64 | 8.2 | 0 |
| 2 | 1 | serializable + retry | 186 | 170 to 193 | 0.000 | 5.09 | 7.8 | 0 |
| 2 | 1 | repeatable read + retry | 162 | 160 to 184 | 0.000 | 5.81 | 11.0 | 0 |
| 2 | 8 | row lock | 181 | 172 to 216 | 0.000 | 17.42 | 537.9 | 0 |
| 2 | 8 | serializable + retry | 178 | 168 to 186 | 1.251 | 5.27 | 316.9 | 310 |
| 2 | 8 | repeatable read + retry | 181 | 161 to 186 | 1.209 | 5.14 | 275.0 | 339 |
| 2 | 32 | row lock | 201 | 123 to 204 | 0.000 | 112.00 | 734.0 | 0 |
| 2 | 32 | serializable + retry | 167 | 167 to 190 | 5.602 | 5.50 | 427.3 | 1562 |
| 2 | 32 | repeatable read + retry | 184 | 167 to 192 | 5.114 | 5.15 | 396.6 | 1582 |
| 1024 | 1 | row lock | 174 | 174 to 195 | 0.000 | 5.67 | 7.4 | 0 |
| 1024 | 1 | serializable + retry | 171 | 156 to 197 | 0.000 | 5.99 | 8.4 | 0 |
| 1024 | 1 | repeatable read + retry | 169 | 169 to 203 | 0.000 | 5.27 | 8.5 | 0 |
| 1024 | 8 | row lock | 793 | 770 to 900 | 0.000 | 8.62 | 18.9 | 0 |
| 1024 | 8 | serializable + retry | 818 | 761 to 845 | 0.025 | 8.55 | 18.1 | 0 |
| 1024 | 8 | repeatable read + retry | 798 | 560 to 830 | 0.021 | 8.55 | 20.2 | 0 |
| 1024 | 32 | row lock | 2,833 | 2,575 to 3,075 | 0.000 | 8.90 | 27.8 | 0 |
| 1024 | 32 | serializable + retry | 2,924 | 2,803 to 3,067 | 0.110 | 8.69 | 26.4 | 5 |
| 1024 | 32 | repeatable read + retry | 2,805 | 2,672 to 3,256 | 0.089 | 8.61 | 29.5 | 0 |
