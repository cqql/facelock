#!/usr/bin/env python

import argparse
import os
import re

import imageio
import numpy as np
import tensorflow as tf


def model_paths(dir):
    files = os.listdir(dir)
    meta_files = [f for f in files if f.endswith(".meta")]

    if len(meta_files) != 1:
        raise Exception(f"There should be exactly one .meta file in {dir}")

    ckpt_files = [f for f in files if re.search("\\.ckpt-(.+)\\.index", f)]
    latest_ckpt_index = max(
        ckpt_files,
        key=lambda f: int(re.search("\\.ckpt-(.+)\\.index", f).groups(1)[0]))
    latest_ckpt = latest_ckpt_index[:-6]

    return os.path.join(dir, meta_files[0]), os.path.join(dir, latest_ckpt)


def prewhiten(img):
    mu = np.mean(img)
    std = np.std(img)

    return (img - mu) / max(std, 1.0 / np.sqrt(img.size))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("-o", default="embeddings.csv", help="Write embeddings here")
    parser.add_argument("model", help="Path to model directory")
    parser.add_argument("faces", nargs="*", help="Face crops")
    args = parser.parse_args()

    model_dir = args.model
    face_paths = args.faces
    out_path = args.o

    print("Import meta graph")
    meta_path, checkpoint_path = model_paths(model_dir)
    saver = tf.train.import_meta_graph(meta_path)

    with tf.Session() as sess:
        print("Restore checkpoint")
        saver.restore(sess, checkpoint_path)

        g = tf.get_default_graph()
        images_ph = g.get_tensor_by_name("input:0")
        embeddings_ph = g.get_tensor_by_name("embeddings:0")
        phase_train_ph = g.get_tensor_by_name("phase_train:0")

        print("Read faces")
        faces = [imageio.imread(f) for f in face_paths]
        # Discard a potential alpha channel
        faces = [f[:, :, :3] for f in faces]
        faces = [prewhiten(f) for f in faces]
        faces = np.stack(faces)

        print("Compute embeddings")
        feed_dict = {images_ph: faces, phase_train_ph: False}
        embeddings, = sess.run([embeddings_ph], feed_dict)

        np.savetxt(out_path, embeddings, delimiter=",")


if __name__ == "__main__":
    main()
