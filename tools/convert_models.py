"""ONNX -> TFLite for WeatherWall (run by .github/workflows/models.yml).

u2netp   : rembg u2netp.onnx, 7 outputs -> keep only d0 (fused map), input [1,3,320,320] NCHW.
skywater : SkyWater-Seg SegFormer-B2 fp32 ONNX, logits [1,4,H,W] -> Softmax(axis=1) -> class 1 (sky),
           single output [1,1,H,W] in [0,1], input [1,3,384,384] NCHW.
Each result is checked against onnxruntime on random input; the script exits non-zero on mismatch.
"""
import hashlib, os, subprocess, sys, urllib.request
import numpy as np, onnx, onnxruntime as ort
from onnx import TensorProto, helper, numpy_helper, shape_inference, utils

OUT = "models_out"; WORK = "work"
os.makedirs(OUT, exist_ok=True); os.makedirs(WORK, exist_ok=True)

SRC = {
    "u2netp": ("https://github.com/danielgatis/rembg/releases/download/v0.0.0/u2netp.onnx", "8e83ca70e441ab06c318d82300c84806"),
    "skywater": ("https://huggingface.co/Realcat/skywater_seg/resolve/main/skywater_segformer_b2_fp32.onnx", None),
}


def get(name):
    url, md5 = SRC[name]; p = f"{WORK}/{name}_orig.onnx"
    if not os.path.exists(p):
        print("download", url); urllib.request.urlretrieve(url, p)
    if md5:
        got = hashlib.md5(open(p, "rb").read()).hexdigest()
        assert got == md5, f"{name}: md5 {got} != {md5}"
    print(name, "size", os.path.getsize(p))
    return p


def prep_u2netp(src, dst, side):
    m = onnx.load(src)
    inp = m.graph.input[0].name; out0 = m.graph.output[0].name
    print("u2netp input", inp, "outputs", [o.name for o in m.graph.output])
    utils.extract_model(src, dst, [inp], [out0])
    return inp


def prep_skywater(src, dst, side):
    m = onnx.load(src)
    opset = max(o.version for o in m.opset_import if o.domain in ("", "ai.onnx"))
    assert opset >= 13, f"opset {opset} < 13: Softmax(axis=1) would be wrong"
    g = m.graph; inp = g.input[0].name; logits = g.output[0].name
    dims = [d.dim_value for d in g.output[0].type.tensor_type.shape.dim]
    print("skywater input", inp, "logits", logits, "dims", dims, "opset", opset)
    assert len(dims) == 4 and dims[1] == 4, f"expected [N,4,H,W] logits, got {dims}"
    g.node.append(helper.make_node("Softmax", [logits], ["sky_sm"], axis=1))
    g.initializer.append(numpy_helper.from_array(np.array([1], dtype=np.int64), "sky_idx"))
    g.node.append(helper.make_node("Gather", ["sky_sm", "sky_idx"], ["sky_prob"], axis=1))
    del g.output[:]
    g.output.append(helper.make_tensor_value_info("sky_prob", TensorProto.FLOAT, None))
    m = shape_inference.infer_shapes(m)
    onnx.save(m, dst)
    return inp


def convert(name, side, prep):
    src = get(name); mod = f"{WORK}/{name}.onnx"
    inp = prep(src, mod, side)
    od = f"{WORK}/{name}_tf"
    subprocess.run(["onnx2tf", "-i", mod, "-o", od, "-ois", f"{inp}:1,3,{side},{side}", "-kat", inp, "-osd"], check=True)
    cand = [f for f in os.listdir(od) if f.endswith("_float32.tflite")]
    assert cand, f"no float32 tflite in {od}: {os.listdir(od)}"
    dst = f"{OUT}/{name}.tflite"
    os.replace(f"{od}/{cand[0]}", dst)
    check(name, mod, inp, dst, side)


def check(name, onnx_path, inp, tflite, side):
    from ai_edge_litert.interpreter import Interpreter
    x = np.random.default_rng(0).normal(size=(1, 3, side, side)).astype(np.float32)
    ref = ort.InferenceSession(onnx_path, providers=["CPUExecutionProvider"]).run(None, {inp: x})[0]
    it = Interpreter(model_path=tflite); it.allocate_tensors()
    i0 = it.get_input_details()[0]; o0 = it.get_output_details()[0]
    print(name, "tflite input", i0["shape"], "output", o0["shape"], "onnx output", ref.shape)
    assert list(i0["shape"]) == [1, 3, side, side], "input is not NCHW"
    it.set_tensor(i0["index"], x); it.invoke()
    got = it.get_tensor(o0["index"])
    assert got.size == ref.size, f"size {got.size} vs {ref.size}"
    diff = float(np.abs(got.reshape(-1) - ref.reshape(-1)).max())
    print(name, "max |tflite-onnx| =", diff, "size MB =", os.path.getsize(tflite) / 1e6)
    assert diff < 1e-2, f"{name}: mismatch {diff}"


if __name__ == "__main__":
    which = sys.argv[1:] or ["u2netp", "skywater"]
    if "u2netp" in which: convert("u2netp", 320, prep_u2netp)
    if "skywater" in which: convert("skywater", 384, prep_skywater)
