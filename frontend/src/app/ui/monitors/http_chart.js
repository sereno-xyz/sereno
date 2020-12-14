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
  const onMouseOut = params["onMouseOut"];
  const onMouseOver = params["onMouseOver"];

  clear(node);

  const svg = (d3.select(node).append("svg")
               .attr("viewBox", [0, 0, width, height])
               .attr("preserveAspectRatio", "none")
               .attr("width", width)
               .attr("height", height));

  const barWidth = 10;
  const bottomMargin = 0;

  const oneDay = dt.Duration.fromObject({days: 1});
  const endDate = truncateToDay(dt.DateTime.utc()); //.plus(dt.Duration.fromObject({hours: 12}));
  const startDate = endDate.minus(dt.Duration.fromObject({days: 90}));
  // console.log(endDate.toString())
  // console.log(startDate.toString())

  const x = (d3.scaleUtc()
             .domain([startDate, endDate])
             .rangeRound([0, width]));

  const y = (d3.scaleLinear()
             .domain([d3.max([300, d3.max(data, d => d["avg"])]), 0])
             .rangeRound([0, height-bottomMargin]));

  const ghostData = (() => {
    const totalBars = 90;
    let data = [];
    let lastDateTime = endDate;

    for (let i=0; i<totalBars; i++) {
      let newTs = lastDateTime.minus(oneDay);
      lastDateTime = newTs;
      data.push({ts: newTs});
    }
    data.reverse();
    return data;
  })();

  svg.append("g")
    .attr("fill", "var(--color-gray-20)")
    .attr("opacity", "0.15")
    .attr("style", "pointer-events:none")
    .selectAll("rect")
    .data(ghostData)
    .join("rect")
    .attr("x", (d, index) => {
      return x(d["ts"]);
    })
    .attr("y", (d) => {
      return 0;
    })
    .attr("width", (d) => {
      return barWidth;
    })
    .attr("height", (d) => {
      return height;
    });

  svg.append("g")
    .attr("fill", "var(--color-primary-light)")
    .selectAll("rect")
    .data(data)
    .join("rect")
    .attr("data-index", (d, index) => index)
    .attr("x", (d, index) => {
      return x(d["ts"].toUTC());
    })
    .attr("y", (d) => {
      return y(d["avg"]);
    })
    .attr("width", (d) => {
      return barWidth;
    })
    .attr("height", (d) => {
      return y(0) - y(d["avg"]);
    });

  svg.append("g")
    .style("cursor", "pointer")
    .selectAll("rect")
    .data(data)
    .join("rect")
    .attr("opacity", "0")
    .attr("data-index", (d, index) => index)
    .attr("x", (d, index) => {
      return x(d["ts"]);
    })
    .attr("y", (d) => {
      return 0;
    })
    .attr("width", (d) => {
      return barWidth;
    })
    .attr("height", (d) => {
      return height;
    })
    .on("mouseover", function(d) {
      const target = d3.select(this);
      target
        .attr("fill", "var(--color-gray-50)")
        .attr("opacity", "0.2");

      const index = parseInt(target.attr("data-index"), 10);
      onMouseOver(index);
    })
    .on("mouseout", function() {
      const target = d3.select(this);
      target.attr("opacity", "0");


      onMouseOut();
    });


  return svg.node();
}
