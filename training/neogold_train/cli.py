"""
Command-line interface for the NeoGold training pipeline.
"""

from __future__ import annotations
import argparse
import sys
from pathlib import Path
from neogold_train.pipeline import run_training_pipeline
from neogold_train.parity import verify_all_golden_parity


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="neogold-train",
        description="NeoGold reproducible model training pipeline (TC-OPS-07, TC-EST-05)",
    )
    subparsers = parser.add_subparsers(dest="command", help="Sub-commands")

    # train
    train_parser = subparsers.add_parser("train", help="Run model training pipeline")
    train_parser.add_argument(
        "--data-source",
        choices=["bench", "partner"],
        default="bench",
        help="Training data source (bench or partner)",
    )
    train_parser.add_argument(
        "--partner-dir",
        type=Path,
        default=None,
        help="Path to de-identified partner export directory",
    )
    train_parser.add_argument(
        "--output-model",
        type=Path,
        default=None,
        help="Destination path for generated model.json",
    )
    train_parser.add_argument(
        "--output-card",
        type=Path,
        default=None,
        help="Destination path for MODEL_CARD.md",
    )
    train_parser.add_argument(
        "--seed",
        type=int,
        default=42,
        help="Random seed for grouped splits and cross-validation",
    )

    # parity
    parity_parser = subparsers.add_parser("parity", help="Verify golden vector parity (TC-EST-01)")
    parity_parser.add_argument(
        "--tolerance",
        type=float,
        default=1e-4,
        help="Maximum allowable difference against golden vectors (default 1e-4)",
    )

    args = parser.parse_args(argv)
    if args.command == "train" or args.command is None:
        source = getattr(args, "data_source", "bench")
        partner_dir = getattr(args, "partner_dir", None)
        out_model = getattr(args, "output_model", None)
        out_card = getattr(args, "output_card", None)
        seed = getattr(args, "seed", 42)

        run_training_pipeline(
            data_source=source,
            partner_export_dir=partner_dir,
            output_model_path=out_model,
            output_card_path=out_card,
            seed=seed,
        )
        return 0

    elif args.command == "parity":
        tol = getattr(args, "tolerance", 1e-4)
        results = verify_all_golden_parity(tolerance=tol)
        print(f"Parity verified: {results}")
        return 0

    else:
        parser.print_help()
        return 1


if __name__ == "__main__":
    sys.exit(main())
