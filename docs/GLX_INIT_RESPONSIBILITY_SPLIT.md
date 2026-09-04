# GLX._init Responsibility Split

Patch 020 is a bytecode-evidence milestone only.

No renderer code is changed.

Canonical generated evidence:

_dropoff/state/020_glx_init/responsibility-census.txt

Exact extracted method:

_dropoff/state/020_glx_init/GLX._init.javap.txt

## Gate

Patch 021 may implement a renderer-initialization backend seam only after this
report identifies every responsibility inside GLX._init(IZ)V.

The rule remains: replace responsibilities, not random OpenGL instructions.