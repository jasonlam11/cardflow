from cardflow_sim.__main__ import main
from cardflow_sim.dataset import read_csv


def test_dataset_command_writes_file(tmp_path, capsys):
    out = tmp_path / "d.csv"
    assert main(["dataset", "--out", str(out), "--cards", "20", "--days", "10", "--seed", "1"]) == 0
    assert "wrote" in capsys.readouterr().out
    assert len(read_csv(str(out))) > 0
