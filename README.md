### Synopsis

Yet another compiler. This time following modern approaches:

- Single unified [sea-of-nodes](
  http://grothoff.org/christian/teaching/2007/3353/papers/click95simple.pdf) SSA IR
- [GVN, GCM](
  https://pdfs.semanticscholar.org/9834/a7794acc843e3f3d471275b70c6664a6fd9f.pdf)
  and [SCCP](
  http://citeseerx.ist.psu.edu/viewdoc/download?doi=10.1.1.17.8510&rep=rep1&type=pdf)
- [LSRA w/ optimal splitting](
  https://www.usenix.org/legacy/events/vee05/full_papers/p132-wimmer.pdf)
