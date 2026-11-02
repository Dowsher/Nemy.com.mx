"""Live adapter for the byte-for-byte user supplied reference; no trained model.

Replay starts at a fixed session origin. Unlike offline run_pipeline, this adapter
does not flush the last open gesture or artificially end an episode. Features
wait for their two seconds of real post-context. Missing data remains missing.
"""
import json
import math
import numpy as np
import visio_detector as ref

SOURCE_SHA256 = "7319dea21fb3e94c46f1f84d469c417b6c2bd298382d0f05c8ad2150e224d55d"

def clean(x):
    if isinstance(x, dict): return {str(k): clean(v) for k, v in x.items()}
    if isinstance(x, (list, tuple)): return [clean(v) for v in x]
    if isinstance(x, np.ndarray): return clean(x.tolist())
    if isinstance(x, (float, np.floating)): return float(x) if math.isfinite(x) else None
    if isinstance(x, (np.integer,)): return int(x)
    if isinstance(x, (np.bool_,)): return bool(x)
    return x

def evaluate(payload):
    p = json.loads(payload)
    cfg = ref.Config()
    a, w = np.asarray(p['acc'], float), np.asarray(p['gyro'], float)
    if len(a) < 50 or len(w) < 50:
        return json.dumps({'state': 'WAITING', 'gestures': [], 'S': 0})
    # Timestamp integers must not be round-tripped through float64 above 2**53.
    at = np.asarray([r[0] for r in p['acc']], np.int64)
    wt = np.asarray([r[0] for r in p['gyro']], np.int64)
    end = min(int(at[-1]), int(wt[-1]))
    start = int(at[0])
    t, acc, aok = ref.resample_uniform(at, a[:, 1:4], cfg.fs, start, end, cfg.max_interp_gap_s)
    _, gyro, wok = ref.resample_uniform(wt, w[:, 1:4], cfg.fs, start, end, cfg.max_interp_gap_s)
    mount = ref.Mounting.from_config(p.get('wrist', 'L'), p.get('crown_hand', True))
    d = ref.derive(t, acc, aok, gyro, wok, cfg, mount)
    sg, scorer, epi = ref.Segmenter(cfg), ref.BootstrapScorer(), ref.EpisodeDetector(cfg)
    policy = ref.DecisionPolicy(cfg, alerts_enabled=scorer.released)
    gestures, episodes, actions = [], [], []
    state, excursion = 0, float('nan')
    screen = np.asarray(p.get('screen_ns', []), np.int64)
    screen = (screen-start)*1e-9
    for i in range(len(t)):
        seg, excursion, state = sg.step(i, float(t[i]), bool(d.valid[i]), float(d.theta_s[i]), float(d.dtheta[i]), d.g_dev[i])
        if seg is None or seg.t_end > t[-1]-cfg.post_ctx_s:
            continue
        features = ref.extract_features(seg, d, cfg, None, None, screen)
        score, llr, novel = scorer.predict(features)
        event = ref.GestureEvent(t=seg.t_end, p=score, llr=llr, novel=novel, seg=seg)
        evs = epi.update(event)
        for e in evs: actions.extend(policy.on_event(e))
        episodes.extend(evs)
        gestures.append({'segment': seg.to_dict(), 'score': score, 'llr': llr,
                         'features': features, 'S': epi.S})
    if len(t): episodes.extend(epi.tick(float(t[-1])))
    result = {'version': ref.VERSION, 'source_sha256': SOURCE_SHA256, 'scorer': scorer.name,
              'released': scorer.released, 'origin_ns': start, 'last_ns': end,
              'state': ref.STATE_NAMES[state], 'theta': d.theta_s[-1] if len(t) else None,
              'excursion': excursion, 'S': epi.S, 'valid_fraction': float(np.mean(d.valid)),
              'mount': {'sx': mount.sx, 'sy': mount.sy, 'calibrated': False},
              'config': ref.asdict(cfg), 'gestures': gestures, 'episodes': episodes, 'actions': actions}
    return json.dumps(clean(result), allow_nan=False, separators=(',', ':'))
