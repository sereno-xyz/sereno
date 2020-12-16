import dt from "luxon";
import d3 from "../../util/d3";

export function clear(node) {
  d3.select(node).selectAll("svg").remove();
}

function truncateToDay(dtime) {
  return dt.DateTime.utc(dtime.year, dtime.month, dtime.day);
}

export function render(node, params) {
  const width = params["width"];
  const height = params["height"];
  const data = params["data"];

  clear(node);

  const svg = (d3.select(node).append("svg")
               .attr("viewBox", [0, 0, width, height])
               .attr("preserveAspectRatio", "none")
               .attr("width", width)
               .attr("height", height));

  const barWidth = 1;

  let endDate = dt.DateTime.utc();
  let startDate = endDate.minus(dt.Duration.fromObject({hours: 1}));

  if (data.length > 1) {
    endDate = data[data.length-1].ts.toUTC();
    startDate = data[0].ts.toUTC();
  }

  const x = (d3.scaleUtc()
             .domain([startDate, endDate])
             .rangeRound([0, width]));

  const y = (d3.scaleLinear()
             .domain([5, 0])
             .rangeRound([0, height]));

  svg.append("g")
    .attr("transform", "translate(-1 0)")
    .selectAll("rect")
    .data(data)
    .join("rect")
    .attr("fill", (d) => {
      if (d.status === "up") {
        return "var(--color-primary-light)";
      } else {
        return "var(--color-danger)";
      }
    })
    .attr("data-index", (d, index) => index)

    .attr("x", (d, index) => {
      return x(d["ts"].toUTC());
    })
    .attr("y", (d) => {
      return y(5);
    })
    .attr("width", (d) => {
      return barWidth;
    })
    .attr("height", (d) => {
      return y(0) - y(5);
    });

  return svg.node();
}
